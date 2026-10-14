-- V3: Remove default/demo seed data so environment starts clean

-- Remove dependent records first
DELETE FROM activity_feed;
DELETE FROM hr_encounters;
DELETE FROM audit_logs;
DELETE FROM offers;
DELETE FROM interviews;
DELETE FROM referrals;
DELETE FROM application_activities;
DELETE FROM application_notes;
DELETE FROM applications;
DELETE FROM jobs;
DELETE FROM user_invitations;
DELETE FROM user_roles;
DELETE FROM user_credentials;
DELETE FROM users;
DELETE FROM departments;
DELETE FROM organizations;

-- Reset sequences
ALTER SEQUENCE IF EXISTS organizations_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS departments_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS users_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS user_credentials_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS user_roles_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS user_invitations_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS jobs_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS applications_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS application_notes_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS application_activities_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS referrals_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS interviews_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS offers_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS audit_logs_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS hr_encounters_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS activity_feed_id_seq RESTART WITH 1;
