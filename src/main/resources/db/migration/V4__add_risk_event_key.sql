ALTER TABLE risk_events
    ADD COLUMN event_key UUID,
    ADD CONSTRAINT uq_risk_events_event_key UNIQUE (event_key);
