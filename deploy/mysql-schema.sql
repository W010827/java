-- ============================================================================
--  工作管理台 · MySQL 建库建表脚本
--  适用 MySQL 5.7 / 8.0（在 5.7.17 上实测通过）
--
--  用法（三种任选）：
--    1) 命令行：  mysql -uroot -p < deploy/mysql-schema.sql
--    2) DBX：     新建连接后打开 SQL 编辑器，把本文件整段贴进去执行
--    3) 不执行：  后端启动时会自动建表（Db#init），本文件主要用于手工建库、
--                 以及给 DBX 里"看得见表结构"用
--
--  脚本幂等，重复执行不会改动已有数据。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `workhub`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;

USE `workhub`;

-- ---------------------------------------------------------------------------
-- 用户与会话
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `users` (
  `id`         VARCHAR(64)  NOT NULL COMMENT '用户 id（uuid hex）',
  `email`      VARCHAR(255) NOT NULL COMMENT '登录邮箱，全局唯一',
  `pwd_salt`   VARCHAR(64)  NOT NULL,
  `pwd_hash`   VARCHAR(128) NOT NULL COMMENT 'PBKDF2-HMAC-SHA256，120000 轮',
  `created_at` VARCHAR(32)  NOT NULL COMMENT 'ISO 字符串，如 2026-10-02T15:38:01',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_users_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `sessions` (
  `token`      VARCHAR(64) NOT NULL COMMENT '登录令牌',
  `user_id`    VARCHAR(64) NOT NULL,
  `created_at` VARCHAR(32) NOT NULL,
  `expires_at` VARCHAR(32) NOT NULL,
  PRIMARY KEY (`token`),
  KEY `idx_sessions_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------------------------------------------------------------------------
-- 软件仓库
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `software_packages` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `owner_id`       VARCHAR(64)  NOT NULL,
  `name`           VARCHAR(255) NOT NULL DEFAULT '',
  `version`        VARCHAR(64)  NOT NULL DEFAULT '',
  `platform`       VARCHAR(128) NOT NULL DEFAULT '',
  `category`       VARCHAR(128) NOT NULL DEFAULT '',
  `description`    TEXT         NULL,
  `file_path`      VARCHAR(512) NOT NULL DEFAULT '',
  `file_name`      VARCHAR(512) NOT NULL DEFAULT '',
  `file_size`      BIGINT       NOT NULL DEFAULT 0,
  `download_count` BIGINT       NOT NULL DEFAULT 0,
  `created_at`     VARCHAR(32)  NOT NULL,
  `updated_at`     VARCHAR(32)  NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_sw_owner` (`owner_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------------------------------------------------------------------------
-- 工作日报（一个日期一条）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `daily_reports` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT,
  `owner_id`      VARCHAR(64) NOT NULL,
  `report_date`   VARCHAR(16) NOT NULL COMMENT 'YYYY-MM-DD',
  `done_today`    TEXT        NULL,
  `plan_tomorrow` TEXT        NULL,
  `issues`        TEXT        NULL,
  `created_at`    VARCHAR(32) NOT NULL,
  `updated_at`    VARCHAR(32) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_report_owner_date` (`owner_id`, `report_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------------------------------------------------------------------------
-- 工作日志
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `work_logs` (
  `id`         BIGINT        NOT NULL AUTO_INCREMENT,
  `owner_id`   VARCHAR(64)   NOT NULL,
  `log_date`   VARCHAR(16)   NOT NULL DEFAULT '' COMMENT 'YYYY-MM-DD',
  `title`      VARCHAR(255)  NOT NULL DEFAULT '',
  `content`    TEXT          NULL,
  `tags`       VARCHAR(1024) NOT NULL DEFAULT '',
  `created_at` VARCHAR(32)   NOT NULL,
  `updated_at` VARCHAR(32)   NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_log_owner` (`owner_id`, `log_date`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------------------------------------------------------------------------
-- 待办
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `todos` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `owner_id`   VARCHAR(64)  NOT NULL,
  `title`      VARCHAR(512) NOT NULL DEFAULT '',
  `detail`     TEXT         NULL,
  `priority`   VARCHAR(32)  NOT NULL DEFAULT 'normal',
  `due_date`   VARCHAR(32)  NULL,
  `status`     VARCHAR(32)  NOT NULL DEFAULT 'pending',
  `done_at`    VARCHAR(32)  NULL,
  `created_at` VARCHAR(32)  NOT NULL,
  `updated_at` VARCHAR(32)  NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_todo_owner` (`owner_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------------------------------------------------------------------------
-- 文档资料
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `documents` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT,
  `owner_id`       VARCHAR(64)   NOT NULL,
  `title`          VARCHAR(512)  NOT NULL DEFAULT '',
  `file_path`      VARCHAR(512)  NOT NULL DEFAULT '',
  `file_name`      VARCHAR(512)  NOT NULL DEFAULT '',
  `file_size`      BIGINT        NOT NULL DEFAULT 0,
  `mime_type`      VARCHAR(128)  NOT NULL DEFAULT '',
  `category`       VARCHAR(128)  NOT NULL DEFAULT '',
  `tags`           VARCHAR(1024) NOT NULL DEFAULT '',
  `note`           TEXT          NULL,
  `download_count` BIGINT        NOT NULL DEFAULT 0,
  `created_at`     VARCHAR(32)   NOT NULL,
  `updated_at`     VARCHAR(32)   NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_doc_owner` (`owner_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
