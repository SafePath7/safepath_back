ALTER TABLE voice_profiles
    ADD COLUMN baseline_details JSONB NULL,
    ADD COLUMN urgent_audio_file_url TEXT NULL;
