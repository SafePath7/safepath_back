package com.safepath.backend.guardian.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** The V1 permission columns are SMALLINT 0/1, not BOOLEAN. */
@Converter
public class FlagConverter implements AttributeConverter<Boolean, Short> {
    @Override
    public Short convertToDatabaseColumn(Boolean value) {
        return (short) (Boolean.TRUE.equals(value) ? 1 : 0);
    }

    @Override
    public Boolean convertToEntityAttribute(Short value) {
        return value != null && value == 1;
    }
}
