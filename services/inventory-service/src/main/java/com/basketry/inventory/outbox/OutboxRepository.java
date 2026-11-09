package com.basketry.inventory.outbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** The relay's queries come with the Kafka side. */
public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findAllByOrderByIdAsc();
}
