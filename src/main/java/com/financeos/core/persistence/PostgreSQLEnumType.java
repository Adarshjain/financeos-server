package com.financeos.core.persistence;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Custom Hibernate UserType for PostgreSQL ENUM types.
 * Maps Java enums to PostgreSQL native enum types.
 */
public class PostgreSQLEnumType implements UserType<Enum<?>> {

    @Override
    public int getSqlType() {
        return Types.OTHER;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<Enum<?>> returnedClass() {
        return (Class<Enum<?>>) (Class<?>) Enum.class;
    }

    @Override
    public boolean equals(Enum<?> x, Enum<?> y) {
        return x == y;
    }

    @Override
    public int hashCode(Enum<?> x) {
        return x == null ? 0 : x.hashCode();
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Enum<?> nullSafeGet(ResultSet rs, int position, SharedSessionContractImplementor session, Object owner)
            throws SQLException {
        String value = rs.getString(position);
        if (rs.wasNull() || value == null) {
            return null;
        }
        
        // Get the actual enum class from the owner field
        Class<?> enumClass = getEnumClass(owner, position);
        if (enumClass != null && enumClass.isEnum()) {
            return Enum.valueOf((Class<Enum>) enumClass, value);
        }
        return null;
    }

    @Override
    public void nullSafeSet(PreparedStatement st, Enum<?> value, int index, SharedSessionContractImplementor session)
            throws SQLException {
        if (value == null) {
            st.setNull(index, Types.OTHER);
        } else {
            st.setObject(index, value.name(), Types.OTHER);
        }
    }

    @Override
    public Enum<?> deepCopy(Enum<?> value) {
        return value;
    }

    @Override
    public boolean isMutable() {
        return false;
    }

    @Override
    public Serializable disassemble(Enum<?> value) {
        return value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Enum<?> assemble(Serializable cached, Object owner) {
        return (Enum<?>) cached;
    }
    
    private Class<?> getEnumClass(Object owner, int position) {
        // This is a simplified implementation
        // The actual enum class should be determined from metadata
        return null;
    }
}

