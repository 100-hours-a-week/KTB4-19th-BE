package com.homes.zipsai.migration.listener;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.springframework.stereotype.Component;

import com.homes.zipsai.migration.config.ConditionalOnMigrationEnabled;
import com.homes.zipsai.migration.domain.OutboxOperation;
import com.homes.zipsai.migration.service.OutboxRecorder;

import lombok.RequiredArgsConstructor;

@Component
@ConditionalOnMigrationEnabled
@RequiredArgsConstructor
public class OutboxEntityListener
    implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    private final EntityManagerFactory entityManagerFactory;
    private final OutboxRecorder outboxRecorder;

    @PostConstruct
    void register() {
        EventListenerRegistry registry = entityManagerFactory.unwrap(SessionFactoryImplementor.class)
            .getEventListenerRegistry();
        registry.appendListeners(EventType.POST_INSERT, this);
        registry.appendListeners(EventType.POST_UPDATE, this);
        registry.appendListeners(EventType.POST_DELETE, this);
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        outboxRecorder.record(event.getPersister().getMappedClass(), event.getId(), OutboxOperation.INSERT);
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        outboxRecorder.record(event.getPersister().getMappedClass(), event.getId(), OutboxOperation.UPDATE);
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        outboxRecorder.record(event.getPersister().getMappedClass(), event.getId(), OutboxOperation.DELETE);
    }
}
