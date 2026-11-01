package com.financeos.domain.category;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.report.engine.SqlPredicates;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionTag;
import com.financeos.domain.transaction.TransactionTagRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class TagService {

    private static final int CHUNK = 500;

    private final TagRepository tagRepository;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionTagRepository transactionTagRepository;

    public TagService(TagRepository tagRepository, UserRepository userRepository,
                      TransactionRepository transactionRepository,
                      TransactionTagRepository transactionTagRepository) {
        this.tagRepository = tagRepository;
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.transactionTagRepository = transactionTagRepository;
    }

    /** A tag with how many transactions carry it. */
    public record TagWithCount(Tag tag, long transactionCount) {
    }

    public Tag createTag(String name) {
        UUID userId = UserContext.getCurrentUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        // Check for existing by name to prevent duplicates (also enforced by a
        // DB unique constraint on (user_id, name))
        String trimmedName = name.trim();
        return tagRepository.findByNameAndUser(trimmedName, user)
                .orElseGet(() -> tagRepository.save(new Tag(trimmedName, user)));
    }

    @Transactional(readOnly = true)
    public List<TagWithCount> getAllTags() {
        List<Tag> tags = tagRepository.findByUserId(UserContext.getCurrentUserId()).stream()
                .sorted(java.util.Comparator.comparing((Tag t) -> t.getName().toLowerCase()).thenComparing(Tag::getId))
                .toList();
        return withCounts(tags);
    }

    @Transactional(readOnly = true)
    public Page<TagWithCount> searchTags(String search, Pageable pageable) {
        UUID userId = UserContext.getCurrentUserId();
        String pattern = search == null || search.isBlank()
                ? "%"
                : "%" + SqlPredicates.escapeLike(search.trim().toLowerCase()) + "%";
        Page<Tag> page = tagRepository.findPage(userId, pattern,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        Map<UUID, Long> counts = counts(page.getContent());
        return page.map(t -> new TagWithCount(t, counts.getOrDefault(t.getId(), 0L)));
    }

    @Transactional(readOnly = true)
    public Tag getTag(UUID id) {
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tag", id));

        // SECURITY: Explicit ownership check
        UUID currentUserId = UserContext.getCurrentUserId();
        if (!tag.getUser().getId().equals(currentUserId)) {
            throw new ValidationException("You do not have permission to access this tag.");
        }

        return tag;
    }

    @Transactional(readOnly = true)
    public TagWithCount getTagWithCount(UUID id) {
        Tag tag = getTag(id);
        return new TagWithCount(tag, counts(List.of(tag)).getOrDefault(tag.getId(), 0L));
    }

    public Tag updateTag(UUID id, String newName) {
        Tag tag = getTag(id); // Reuses ownership check

        // Check for duplicates for this user
        String trimmedName = newName.trim();
        tagRepository.findByNameAndUser(trimmedName, tag.getUser())
                .ifPresent(existing -> {
                    if (!existing.getId().equals(id)) {
                        throw new ValidationException("A tag with this name already exists.");
                    }
                });

        tag.setName(trimmedName);
        return tagRepository.save(tag);
    }

    public void deleteTag(UUID id) {
        Tag tag = getTag(id); // Reuses ownership check
        tagRepository.delete(tag);
    }

    /**
     * Adds every given tag to every given transaction. Idempotent (existing links are left as they
     * are) and user-scoped: unknown or foreign transactions are skipped, a foreign or unknown tag
     * rejects the whole request.
     */
    public com.financeos.api.tag.dto.BulkAddTagsResponse bulkAdd(List<UUID> transactionIds, List<UUID> tagIds) {
        UUID userId = UserContext.getCurrentUserId();
        Set<UUID> wantedTags = new HashSet<>(tagIds);
        List<Tag> tags = tagRepository.findByUserIdAndIdIn(userId, wantedTags);
        if (tags.size() != wantedTags.size()) {
            throw new ResourceNotFoundException("One or more tags not found");
        }

        List<UUID> txnIds = transactionIds.stream().distinct().toList();
        int found = 0;
        List<TransactionTag> toSave = new ArrayList<>();
        for (int from = 0; from < txnIds.size(); from += CHUNK) {
            List<UUID> chunk = txnIds.subList(from, Math.min(from + CHUNK, txnIds.size()));
            List<Transaction> txns = transactionRepository.findAllByIdInAndUserId(chunk, userId);
            found += txns.size();
            if (txns.isEmpty()) {
                continue;
            }
            Set<String> existing = transactionTagRepository
                    .findLinks(txns.stream().map(Transaction::getId).toList(), wantedTags).stream()
                    .map(l -> l.getTransactionId() + "|" + l.getTagId())
                    .collect(Collectors.toSet());
            for (Transaction txn : txns) {
                for (Tag tag : tags) {
                    if (!existing.contains(txn.getId() + "|" + tag.getId())) {
                        toSave.add(new TransactionTag(txn, tag));
                    }
                }
            }
        }
        if (!toSave.isEmpty()) {
            transactionTagRepository.saveAll(toSave);
        }
        return new com.financeos.api.tag.dto.BulkAddTagsResponse(found, toSave.size());
    }

    private List<TagWithCount> withCounts(List<Tag> tags) {
        Map<UUID, Long> counts = counts(tags);
        return tags.stream().map(t -> new TagWithCount(t, counts.getOrDefault(t.getId(), 0L))).toList();
    }

    /** Transaction counts for the given tags, in chunks (Oracle caps IN lists at 1000). */
    private Map<UUID, Long> counts(List<Tag> tags) {
        Map<UUID, Long> result = new HashMap<>();
        List<UUID> ids = tags.stream().map(Tag::getId).toList();
        for (int from = 0; from < ids.size(); from += CHUNK) {
            tagRepository.countTransactionsByTagIds(ids.subList(from, Math.min(from + CHUNK, ids.size())))
                    .forEach(c -> result.put(c.getTagId(), c.getTotal()));
        }
        return result;
    }
}
