-- Only upgrade the former default; preserve deliberately lower operator limits.
UPDATE system_setting
SET cfg_value = '200', updated_at = NOW()
WHERE cfg_key = 'files.upload-max-mb' AND cfg_value = '50';
