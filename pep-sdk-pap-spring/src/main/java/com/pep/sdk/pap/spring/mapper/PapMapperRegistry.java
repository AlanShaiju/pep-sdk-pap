package com.pep.sdk.pap.spring.mapper;

import com.pep.sdk.pap.model.PapEntity;
import com.pep.sdk.pap.model.PapEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Registry mapping (Entity, Event) pairs to their corresponding PapPayloadMapper beans.
 */
public class PapMapperRegistry {

    private final Map<MapperKey, PapPayloadMapper> mappers = new HashMap<>();

    public PapMapperRegistry(List<PapPayloadMapper> mapperBeans) {
        if (mapperBeans != null) {
            for (PapPayloadMapper mapper : mapperBeans) {
                mappers.put(new MapperKey(mapper.getEntity(), mapper.getEvent()), mapper);
            }
        }
    }

    /**
     * Resolves the mapper for the given entity and event combination.
     */
    public PapPayloadMapper getMapper(PapEntity entity, PapEvent event) {
        PapPayloadMapper mapper = mappers.get(new MapperKey(entity, event));
        if (mapper == null) {
            throw new IllegalArgumentException(
                String.format("No PapPayloadMapper registered for entity '%s' and event '%s'", entity, event)
            );
        }
        return mapper;
    }

    private static final class MapperKey {
        private final PapEntity entity;
        private final PapEvent event;

        public MapperKey(PapEntity entity, PapEvent event) {
            this.entity = entity;
            this.event = event;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MapperKey mapperKey = (MapperKey) o;
            return entity == mapperKey.entity && event == mapperKey.event;
        }

        @Override
        public int hashCode() {
            return Objects.hash(entity, event);
        }
    }
}
