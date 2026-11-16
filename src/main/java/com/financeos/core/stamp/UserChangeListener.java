package com.financeos.core.stamp;

import com.financeos.domain.user.User;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.hibernate.Hibernate;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostCollectionRecreateEvent;
import org.hibernate.event.spi.PostCollectionRecreateEventListener;
import org.hibernate.event.spi.PostCollectionRemoveEvent;
import org.hibernate.event.spi.PostCollectionRemoveEventListener;
import org.hibernate.event.spi.PostCollectionUpdateEvent;
import org.hibernate.event.spi.PostCollectionUpdateEventListener;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.proxy.HibernateProxy;
import org.springframework.stereotype.Component;

/**
 * Tells {@link UserChangeStamps} which user an insert, update or delete (or a change to an entity's collection)
 * belongs to. The owner is read from the entity's own {@code user} or {@code userId} property, or, for a child
 * row without one, from the parent it points at.
 */
@Component
public class UserChangeListener implements PostInsertEventListener, PostUpdateEventListener,
        PostDeleteEventListener, PostCollectionRecreateEventListener, PostCollectionUpdateEventListener,
        PostCollectionRemoveEventListener {

    private final EntityManagerFactory entityManagerFactory;
    private final UserChangeStamps stamps;
    private final Map<String, Set<String>> noiseByEntity = new ConcurrentHashMap<>();

    public UserChangeListener(EntityManagerFactory entityManagerFactory, UserChangeStamps stamps) {
        this.entityManagerFactory = entityManagerFactory;
        this.stamps = stamps;
    }

    @PostConstruct
    void register() {
        EventListenerRegistry registry = entityManagerFactory.unwrap(SessionFactoryImplementor.class)
                .getServiceRegistry().getService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_INSERT, this);
        registry.appendListeners(EventType.POST_UPDATE, this);
        registry.appendListeners(EventType.POST_DELETE, this);
        registry.appendListeners(EventType.POST_COLLECTION_RECREATE, this);
        registry.appendListeners(EventType.POST_COLLECTION_UPDATE, this);
        registry.appendListeners(EventType.POST_COLLECTION_REMOVE, this);
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        mark(event.getPersister(), event.getState());
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        if (onlyNoise(event.getPersister(), event.getDirtyProperties())) {
            return;
        }
        mark(event.getPersister(), event.getState());
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        mark(event.getPersister(), event.getDeletedState());
    }

    @Override
    public void onPostRecreateCollection(PostCollectionRecreateEvent event) {
        markOwner(event.getAffectedOwnerOrNull(), event);
    }

    @Override
    public void onPostUpdateCollection(PostCollectionUpdateEvent event) {
        markOwner(event.getAffectedOwnerOrNull(), event);
    }

    @Override
    public void onPostRemoveCollection(PostCollectionRemoveEvent event) {
        markOwner(event.getAffectedOwnerOrNull(), event);
    }

    private void markOwner(Object owner, org.hibernate.event.spi.AbstractCollectionEvent event) {
        if (owner == null) {
            return;
        }
        EntityPersister persister = event.getSession().getEntityPersister(event.getAffectedOwnerEntityName(), owner);
        mark(persister, persister.getValues(owner));
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    /** True when every changed property is marked {@link NoChangeStamp} (a progress tick, a "last used" time). */
    private boolean onlyNoise(EntityPersister persister, int[] dirty) {
        if (dirty == null || dirty.length == 0) {
            return false;
        }
        Set<String> noise = noiseByEntity.computeIfAbsent(persister.getEntityName(), name -> noiseProperties(persister));
        if (noise.isEmpty()) {
            return false;
        }
        String[] names = persister.getPropertyNames();
        for (int index : dirty) {
            if (!noise.contains(names[index])) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> noiseProperties(EntityPersister persister) {
        Set<String> out = new HashSet<>();
        for (Class<?> c = persister.getMappedClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.isAnnotationPresent(NoChangeStamp.class)) {
                    out.add(field.getName());
                }
            }
        }
        return out;
    }

    private void mark(EntityPersister persister, Object[] state) {
        if (persister.getMappedClass() == UserChangeStamp.class || persister.getMappedClass() == User.class) {
            return;
        }
        UUID owner = owner(persister, state, true);
        if (owner != null) {
            stamps.markChanged(owner);
        }
    }

    /** Own {@code userId} / {@code user}; failing that (one level only) the owner of an already loaded parent. */
    private static UUID owner(EntityPersister persister, Object[] state, boolean viaParent) {
        String[] names = persister.getPropertyNames();
        for (int i = 0; i < names.length; i++) {
            if (state[i] == null) {
                continue;
            }
            if ("userId".equals(names[i])) {
                return toUuid(state[i]);
            }
            if ("user".equals(names[i])) {
                return userIdOf(state[i]);
            }
        }
        if (viaParent) {
            for (int i = 0; i < names.length; i++) {
                Object value = state[i];
                if (value == null || !persister.getPropertyType(names[i]).isEntityType()
                        || !Hibernate.isInitialized(value)) {
                    continue;
                }
                Object real = value instanceof HibernateProxy proxy ? proxy.getHibernateLazyInitializer().getImplementation() : value;
                if (real instanceof User) {
                    continue;
                }
                EntityPersister parent = ((org.hibernate.engine.spi.SessionFactoryImplementor) persister.getFactory())
                        .getMappingMetamodel().getEntityDescriptor(Hibernate.getClass(real));
                UUID owner = owner(parent, parent.getValues(real), false);
                if (owner != null) {
                    return owner;
                }
            }
        }
        return null;
    }

    private static UUID userIdOf(Object user) {
        if (user instanceof HibernateProxy proxy) {
            return toUuid(proxy.getHibernateLazyInitializer().getIdentifier());
        }
        return user instanceof User u ? u.getId() : null;
    }

    private static UUID toUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        try {
            return value == null ? null : UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
