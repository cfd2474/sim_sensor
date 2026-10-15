-- Per-alarm latching (A1 / A2 / STEL / TWA) replaces the meter-wide "alarmLatching" switch,
-- and the built-in sensors respond faster (T90 5 s). See PROJECT_STATE.md chunk 2.6.

-- Meter setups: spell out each alarm's latching. A setup that latched everything keeps A2,
-- STEL and TWA latching but lets A1 clear itself (the industry default the operator asked
-- for); a setup that latched nothing keeps latching nothing.
UPDATE sensor_profiles p
SET definition = jsonb_set(
        p.definition - 'alarmLatching',
        '{channels}',
        (SELECT coalesce(jsonb_agg(
                    ch || jsonb_build_object(
                        'lowAlarmLatching', false,
                        'highAlarmLatching', l.latch,
                        'stelAlarmLatching', l.latch,
                        'twaAlarmLatching', l.latch)
                    ORDER BY ord), '[]'::jsonb)
         FROM jsonb_array_elements(p.definition -> 'channels') WITH ORDINALITY AS c(ch, ord))),
    version    = version + 1,
    updated_at = now()
FROM (SELECT id, coalesce((definition ->> 'alarmLatching')::boolean, true) AS latch FROM sensor_profiles) l
WHERE l.id = p.id;

-- Built-in template: channels still on the old default response time go to 5 s. Values an
-- admin changed stay as they are.
UPDATE sensor_profiles p
SET definition = jsonb_set(
        p.definition,
        '{channels}',
        (SELECT jsonb_agg(
                    CASE
                        WHEN (ch ->> 't90Seconds')::numeric =
                             CASE ch ->> 'id' WHEN 'O2' THEN 15 WHEN 'LEL' THEN 15 WHEN 'CO' THEN 30
                                              WHEN 'H2S' THEN 30 WHEN 'HCN' THEN 60 END
                        THEN jsonb_set(ch, '{t90Seconds}', '5.0'::jsonb)
                        ELSE ch
                    END
                    ORDER BY ord)
         FROM jsonb_array_elements(p.definition -> 'channels') WITH ORDINALITY AS c(ch, ord)))
WHERE p.builtin;

-- Exercise criteria: an explicit meter-wide latching choice becomes the same choice on every
-- alarm of every channel of the exercise's setup; then the old key goes.
UPDATE exercises e
SET criteria_overrides = jsonb_set(
        e.criteria_overrides - 'alarmLatching',
        '{channels}',
        coalesce(e.criteria_overrides -> 'channels', '{}'::jsonb) ||
        (SELECT coalesce(jsonb_object_agg(
                    ch ->> 'id',
                    coalesce(e.criteria_overrides -> 'channels' -> (ch ->> 'id'), '{}'::jsonb) ||
                    jsonb_build_object(
                        'lowAlarmLatching', (e.criteria_overrides ->> 'alarmLatching')::boolean,
                        'highAlarmLatching', (e.criteria_overrides ->> 'alarmLatching')::boolean,
                        'stelAlarmLatching', (e.criteria_overrides ->> 'alarmLatching')::boolean,
                        'twaAlarmLatching', (e.criteria_overrides ->> 'alarmLatching')::boolean)), '{}'::jsonb)
         FROM sensor_profiles p, jsonb_array_elements(p.definition -> 'channels') AS c(ch)
         WHERE p.id = e.profile_id))
WHERE jsonb_typeof(e.criteria_overrides -> 'alarmLatching') = 'boolean';

UPDATE exercises
SET criteria_overrides = criteria_overrides - 'alarmLatching'
WHERE criteria_overrides ? 'alarmLatching';
