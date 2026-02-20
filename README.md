# Stonebranch Disaster Recovery Orchestration Demo

A complete disaster recovery (DR) orchestration solution built on **Stonebranch Universal Automation Center (UAC)**. This project demonstrates automated daily backups of a MySQL database and NFS file server to Amazon S3, with a full DR failover workflow that boots standby EC2 instances, restores data, and starts the application -- all with human approval gates at every critical phase.

---

## Table of Contents

- [Project Overview](#project-overview)
- [Stonebranch Ecosystem Architecture](#stonebranch-ecosystem-architecture)
- [Deployment Architecture](#deployment-architecture)
- [Application (app/)](#application-app)
  - [Tech Stack](#tech-stack)
  - [Source Code Structure](#source-code-structure)
  - [API Endpoints](#api-endpoints)
  - [Configuration](#configuration)
  - [Running the Application](#running-the-application)
- [Stonebranch Workflows](#stonebranch-workflows)
  - [1. DBBackupFlow -- Database Backup](#1-dbbackupflow----database-backup)
  - [2. FileBackupFlow -- File Server Backup](#2-filebackupflow----file-server-backup)
  - [3. DRFailOverFlow -- Disaster Recovery Failover](#3-drfailoverflow----disaster-recovery-failover)
- [Agent Inventory](#agent-inventory)
- [Workflow Variables Reference](#workflow-variables-reference)
- [Runbook: Executing in Stonebranch UAC](#runbook-executing-in-stonebranch-uac)
  - [Prerequisites](#prerequisites)
  - [Step 1: Import Task Definitions](#step-1-import-task-definitions)
  - [Step 2: Configure Credentials](#step-2-configure-credentials)
  - [Step 3: Verify Agent Connectivity](#step-3-verify-agent-connectivity)
  - [Step 4: Configure Workflow Variables](#step-4-configure-workflow-variables)
  - [Step 5: Schedule Daily Backups](#step-5-schedule-daily-backups)
  - [Step 6: Execute DR Failover](#step-6-execute-dr-failover)
  - [Step 7: Post-Failover Verification](#step-7-post-failover-verification)
- [Repository Structure](#repository-structure)

---

## Project Overview

This project consists of two major components:

| Component | Purpose |
|---|---|
| **`app/`** | A Spring Boot web application (Java 11) that reads/writes to a MySQL database and an NFS-mounted file share. This is the "application under protection" -- the workload that the DR workflows back up and restore. |
| **`stonebranch/`** | Config-as-code JSON definitions for 3 Stonebranch UAC workflows (27 task definitions total) that automate daily backups and DR failover orchestration. |

**DR Strategy:**
1. **Daily** -- `DBBackupFlow` dumps MySQL and uploads to S3; `FileBackupFlow` compresses NFS files and uploads to S3.
2. **On disaster** -- `DRFailOverFlow` boots DR EC2 instances, restores the latest backup from S3, and starts the application on the DR site.

---

## Stonebranch Ecosystem Architecture

```
+===========================================================================+
|                    STONEBRANCH UNIVERSAL AUTOMATION CENTER                  |
+===========================================================================+
|                                                                           |
|  +----------------------------+      +-------------------------------+    |
|  |   OMS / Controller (UC)    |      |   OMS / Controller (UC)       |    |
|  |   CTC On-Premise           |      |   AWS EC2                     |    |
|  |   (Ubuntu VM)              |      |   (DR Site)                   |    |
|  |                            |      |                               |    |
|  |  - Workflow Engine         |      |  - Workflow Engine             |    |
|  |  - Task Scheduler          |      |  - Task Scheduler             |    |
|  |  - Approval Management     |      |  - Approval Management        |    |
|  |  - Variable Resolution     |      |  - Variable Resolution        |    |
|  |  - Credential Vault        |      |  - Credential Vault           |    |
|  |  - Audit / Logging         |      |  - Audit / Logging            |    |
|  |  - Universal Control Plane |      |                               |    |
|  +-------------+--------------+      +---------------+---------------+    |
|                |                                     |                    |
|     Agent Communication (TLS)             Agent Communication (TLS)       |
|                |                                     |                    |
|  +-------------v--------------+      +---------------v---------------+   |
|  |    PRODUCTION AGENTS       |      |      DR AGENTS                |   |
|  |    (CTC On-Premise VMs)    |      |      (AWS EC2 Instances)      |   |
|  |                            |      |                               |   |
|  |  +--------------------+    |      |  +------------------------+   |   |
|  |  | AGNT0003           |    |      |  | AGNT0005               |   |   |
|  |  | ccoestonebranchapp2|    |      |  | ip-10-0-1-242          |   |   |
|  |  | Role: DB Server    |    |      |  | Role: DR Orchestrator  |   |   |
|  |  | - mysqldump        |    |      |  | - AWS CLI (ec2 cmds)   |   |   |
|  |  | - gzip             |    |      |  +------------------------+   |   |
|  |  | - AWS CLI (s3)     |    |      |                               |   |
|  |  +--------------------+    |      |  +------------------------+   |   |
|  |                            |      |  | AGNT0006               |   |   |
|  |  +--------------------+    |      |  | ip-10-0-1-210          |   |   |
|  |  | AGNT0004           |    |      |  | Role: DR App/File Svr  |   |   |
|  |  | ccoestonebranchapp3|    |      |  | - gzip, cp, aws s3     |   |   |
|  |  | Role: File Server  |    |      |  | - Maven (Spring Boot)  |   |   |
|  |  | - gzip             |    |      |  +------------------------+   |   |
|  |  | - AWS CLI (s3)     |    |      |                               |   |
|  |  | - NFS mount access |    |      |  +------------------------+   |   |
|  |  +--------------------+    |      |  | AGNT0007               |   |   |
|  |                            |      |  | ip-10-0-1-144          |   |   |
|  +----------------------------+      |  | Role: DR DB Server     |   |   |
|                                      |  | - mysql client         |   |   |
|                                      |  | - gzip, aws s3         |   |   |
|                                      |  +------------------------+   |   |
|                                      +-------------------------------+   |
|                                                                          |
|  +-------------------------------------------------------------------+   |
|  |                    SHARED SERVICES                                 |   |
|  |                                                                   |   |
|  |  +------------------+  +------------------+  +-----------------+  |   |
|  |  | Amazon S3        |  | Credential Store |  | Approval Engine |  |   |
|  |  | Backup Storage   |  | cred_ccoeadmin   |  | Approvers:      |  |   |
|  |  | ccoe-stonebranch |  | (Stonebranch     |  |  - Momoyo       |  |   |
|  |  | -poc-1           |  |  managed)        |  |  - Fernando     |  |   |
|  |  +------------------+  +------------------+  +-----------------+  |   |
|  +-------------------------------------------------------------------+   |
+===========================================================================+
```

### How the Components Interact

1. **OMS (Operations Manager Server)** is the brain -- it schedules workflows, dispatches tasks to agents, manages variables, and handles approvals.
2. **Universal Agents** are lightweight daemons installed on each managed server. They receive task instructions from the OMS, execute shell commands locally, and report results back.
3. **Universal Control Plane** (on the CTC on-premise OMS) provides centralized governance across both sites.
4. **Amazon S3** serves as the intermediate backup store -- production agents push backups up, DR agents pull them down during failover.
5. **Approval Engine** pauses the DR workflow at 3 critical gates, requiring human sign-off before proceeding.

---

## Deployment Architecture

```
+=============================================================================+
|                          CTC ON-PREMISE (PRODUCTION)                        |
|                              Ubuntu VMs                                     |
+=============================================================================+
|                                                                             |
|  VM 1: OMS + Universal Control Plane                                        |
|  +-----------------------------------------------------------------------+  |
|  |  Stonebranch OMS (Operations Manager Server)                          |  |
|  |  - Universal Controller Engine                                        |  |
|  |  - Web UI (Workflow Designer, Monitoring, Approval Console)           |  |
|  |  - REST API for config-as-code imports                                |  |
|  |  - Universal Control Plane (cross-site orchestration)                 |  |
|  +-----------------------------------------------------------------------+  |
|       |                    |                    |                            |
|       | Agent Conn.        | Agent Conn.        | Agent Conn.               |
|       v                    v                    v                            |
|  +-----------+      +-------------+      +--------------+                   |
|  | VM 2:     |      | VM 3:       |      | VM 4:        |                   |
|  | App Server|      | MySQL Server|      | File Server  |                   |
|  |           |      |             |      |              |                   |
|  | Universal |      | Universal   |      | Universal    |                   |
|  | Agent     |      | Agent       |      | Agent        |                   |
|  | installed |      | (AGNT0003)  |      | (AGNT0004)   |                   |
|  |           |      |             |      |              |                   |
|  | Spring    |      | MySQL 8.x   |      | NFS Server   |                   |
|  | Boot App  |      | db_example  |      | /var/nfs/    |                   |
|  | Port 8080 |      | Port 3306   |      | shared_folder|                   |
|  +-----------+      +------+------+      +------+-------+                   |
|       |                    |                    |                            |
|       |              Daily Backup          Daily Backup                      |
|       |              (DBBackupFlow)        (FileBackupFlow)                  |
|       |                    |                    |                            |
+=======|====================|====================|============================+
        |                    |                    |
        |                    v                    v
        |         +----------------------------+
        |         |       Amazon S3            |
        |         |  ccoe-stonebranch-poc-1    |
        |         |                            |
        |         |  testdb_backup.sql_        |
        |         |    YYYYMMDD.gz             |
        |         |  testfile1_                |
        |         |    YYYYMMDD.gz             |
        |         +----------------------------+
        |                    |                    |
        |              DR Restore            DR Restore
        |              (Phase 2)             (Phase 3)
        |                    |                    |
+=======|====================|====================|============================+
|       |                    |                    |                            |
|       |         AMAZON WEB SERVICES (DR SITE)   |                           |
|       |              AWS EC2 Instances           |                           |
+=======|==========================================|===========================+
|                                                                             |
|  EC2: OMS                                                                   |
|  +-----------------------------------------------------------------------+  |
|  |  Stonebranch OMS (DR Site)                                            |  |
|  |  - Receives workflow dispatches                                       |  |
|  |  - Manages DR agents                                                  |  |
|  +-----------------------------------------------------------------------+  |
|       |                    |                    |                            |
|       | Agent Conn.        | Agent Conn.        | Agent Conn.               |
|       v                    v                    v                            |
|  +-----------+      +-------------+      +--------------+                   |
|  | EC2:      |      | EC2:        |      | EC2:         |                   |
|  | DR App/   |      | DR DB       |      | DR           |                   |
|  | File Svr  |      | Server      |      | Orchestrator |                   |
|  |           |      |             |      |              |                   |
|  | AGNT0006  |      | AGNT0007    |      | AGNT0005     |                   |
|  | 10.0.1.210|      | 10.0.1.144  |      | 10.0.1.242   |                   |
|  |           |      |             |      |              |                   |
|  | Spring    |      | MySQL 8.x   |      | AWS CLI      |                   |
|  | Boot App  |      | (restored)  |      | ec2 start/   |                   |
|  | NFS mount |      |             |      | describe     |                   |
|  | /mnt/nfs_ |      |             |      |              |                   |
|  | client-   |      |             |      | Starts all   |                   |
|  | share/    |      |             |      | 3 EC2s in    |                   |
|  +-----------+      +-------------+      | sequence     |                   |
|                                          +--------------+                   |
|                                                                             |
|  EC2 Instance IDs:                                                          |
|    DB Server:   i-04784d39a4c5c256c                                         |
|    File Server: i-0fdd1546ba7a45cfa                                         |
|    App Server:  i-0d7ae2f9fb987c75e                                         |
|                                                                             |
+=============================================================================+


                    DATA FLOW OVERVIEW

  PRODUCTION (Daily)                    DR (On Disaster)
  ==================                    ================

  MySQL ──mysqldump──> .sql             EC2 Instances
    |                   |                 |
    |              gzip compress     [1] Start (AGNT0005)
    |                   |                 |
    |              aws s3 cp         [2] Download from S3
    |                   |              + Restore MySQL (AGNT0007)
    |                   v                 |
    |              +--------+        [3] Download from S3
    |              |   S3   | ---------> + Restore NFS (AGNT0006)
    |              +--------+             |
    |                   ^            [4] Start Spring Boot
  NFS ──gzip──> .gz ───┘              + Verify (AGNT0006)
  File    aws s3 cp
```

---

## Application (app/)

The `app/` directory contains a **Spring Boot 2.7.18** web application written in Java 11. It serves as the demo workload that is protected by the DR workflows. The application connects to a MySQL database and reads files from an NFS-mounted shared folder.

### Tech Stack

| Component | Version / Detail |
|---|---|
| **Framework** | Spring Boot 2.7.18 |
| **Java** | 11 |
| **Database** | MySQL (via Spring Data JPA + Hibernate) |
| **API Docs** | SpringDoc OpenAPI (Swagger UI at `/swagger-ui.html`) |
| **Build Tool** | Maven (with Maven Wrapper `mvnw`) |
| **MySQL Connector** | `mysql-connector-j` |
| **Testing** | JUnit 5 + Testcontainers (MySQL) |

### Source Code Structure

```
app/
  src/main/java/com/example/accessingdatamysql/
    AccessingDataMysqlApplication.java   # Spring Boot entry point
    MainController.java                  # Legacy controller (/ endpoint, returns DB + file data)
    User.java                            # JPA entity: id, name, email
    UserRepository.java                  # Spring Data CrudRepository<User, Integer>
    UserController.java                  # REST API: /api/users (CRUD)
    FileController.java                  # REST API: /api/file (NFS file operations)
  src/main/resources/
    application.properties               # DB connection, JPA config
  pom.xml                               # Maven build with Spring Boot, JPA, MySQL, OpenAPI
  run-app.sh                            # Helper script to start the app
  check-java.sh                         # Java version checker/installer guide
  compose.yml                           # Docker Compose for local MySQL (development)
```

### API Endpoints

#### User API (`/api/users`) -- `UserController.java`

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/users` | List all users |
| `GET` | `/api/users/{id}` | Get user by ID |
| `POST` | `/api/users` | Create new user (JSON body: `name`, `email`) |
| `PUT` | `/api/users/{id}` | Update user (JSON body: `name`, `email`) |
| `DELETE` | `/api/users/{id}` | Delete user |

#### File API (`/api/file`) -- `FileController.java`

Operates on files in `/mnt/nfs_clientshare/` (the NFS-mounted shared folder).

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/file` | List all files in the NFS folder |
| `GET` | `/api/file/{filename}` | Get file content (text/plain) |
| `POST` | `/api/file/{filename}` | Create new file with default content |
| `PUT` | `/api/file/{filename}` | Update file content (text/plain body) |
| `DELETE` | `/api/file/{filename}` | Delete a file |

The `FileController` includes path traversal protection via `resolveAndValidate()` which ensures all file operations are constrained to the `BASE_DIR`.

#### Legacy Endpoint (`MainController.java`)

The `MainController` provides a combined response returning a "hello world" message, all database users, and the content of `/mnt/nfs_clientshare/testfile1`. This controller's route mappings are currently commented out but the methods remain for reference.

### Configuration

**`application.properties`:**

| Property | Value | Description |
|---|---|---|
| `spring.datasource.url` | `jdbc:mysql://10.12.197.214:3306/db_example` | MySQL connection (production on-premise IP) |
| `spring.datasource.username` | `springappuser` | MySQL user |
| `spring.datasource.password` | `password` | MySQL password |
| `spring.jpa.hibernate.ddl-auto` | `update` | Auto-create/update schema from entities |
| `spring.datasource.driver-class-name` | `com.mysql.cj.jdbc.Driver` | MySQL JDBC driver |
| `spring.jackson.serialization.indent_output` | `true` | Pretty-print JSON responses |

### Running the Application

**On the production/DR server:**

```bash
cd /app/springapp-05/gs-accessing-data-mysql/complete
./mvnw spring-boot:run
```

**Using the helper script:**

```bash
cd app/
./run-app.sh
```

**For local development with Docker Compose:**

```bash
cd app/
docker compose up -d   # Starts MySQL
./mvnw spring-boot:run
```

---

## Stonebranch Workflows

All workflow and task definitions are stored as JSON files under `stonebranch/` and can be imported into Stonebranch UAC via the REST API or web UI.

### 1. DBBackupFlow -- Database Backup

**Purpose:** Daily automated backup of the MySQL `db_example` database to Amazon S3.

**Agent:** `ccoestonebranchapp2 - AGNT0003` (Production DB Server, CTC On-Premise)

**Credentials:** `cred_ccoeadmin`

```
+-----------------+     +------------------+     +------------------+     +--------------------+
| DBServerBKUP    | --> | DBServerBKUP     | --> | DBServerBKUP     | --> | DBServerBKUP       |
| _MAIN           |     | _ZIP             |     | _S3UP            |     | _S3Check           |
| (mysqldump)     |     | (gzip compress)  |     | (aws s3 cp)      |     | (s3api head-object)|
+-----------------+     +------------------+     +------------------+     +--------------------+
      ~3s                      ~3s                     ~11s                      ~7s
```

| Step | Task | What It Does |
|---|---|---|
| 1 | **DBServerBKUP_MAIN** | `mysqldump -u springappuser -p'password' db_example > /tmp/testdb_backup.sql` |
| 2 | **DBServerBKUP_ZIP** | `gzip -c /tmp/testdb_backup.sql > /tmp/testdb_backup.sql_YYYYMMDD.gz` |
| 3 | **DBServerBKUP_S3UP** | `aws s3 cp /tmp/testdb_backup.sql_YYYYMMDD.gz s3://ccoe-stonebranch-poc-1/` |
| 4 | **DBServerBKUP_S3Check** | Verifies the file exists in S3 via `s3api head-object`; exits `1` on failure |

**Runtime:** ~40 seconds average | 24 runs total | Scheduled daily at 08:00 (UTC+8)

---

### 2. FileBackupFlow -- File Server Backup

**Purpose:** Daily automated backup of NFS files to Amazon S3.

**Agent:** `ccoestonebranchapp3 - AGNT0004` (Production File Server, CTC On-Premise)

**Credentials:** `cred_ccoeadmin`

```
+------------------+     +-------------------+     +---------------------+
| FileServerBKUP   | --> | FileServerBKUP    | --> | FileServerBKUP      |
| _ZIP             |     | _S3UP             |     | _S3Check            |
| (gzip compress)  |     | (aws s3 cp)       |     | (s3api head-object) |
+------------------+     +-------------------+     +---------------------+
       ~3s                       ~11s                       ~7s
```

| Step | Task | What It Does |
|---|---|---|
| 1 | **FileServerBKUP_ZIP** | `gzip -c /var/nfs/shared_folder/testfile1 > /var/nfs/shared_folder/testfile1_YYYYMMDD.gz` |
| 2 | **FileServerBKUP_S3UP** | `aws s3 cp testfile1_YYYYMMDD.gz s3://ccoe-stonebranch-poc-1/` |
| 3 | **FileServerBKUP_S3Check** | Verifies the file exists in S3; exits `1` on failure |

**Runtime:** ~21 seconds average | 20 runs total | Scheduled daily at 08:00 (UTC+8)

---

### 3. DRFailOverFlow -- Disaster Recovery Failover

**Purpose:** Full DR failover -- boots DR infrastructure, restores data from the latest daily backup, and starts the application. Includes 3 manual approval gates for human oversight.

**20 task instances** across **4 phases** with **3 approval gates**.

```
PHASE 1: Infrastructure Startup (AGNT0005 - DR Orchestrator)
+=============================================================================+
| DBServer    DBServer    FileServer   FileServer   APServer    APServer      |
| _Start  --> _Check  --> _Start   --> _Check   --> _Start  --> _Check        |
|  ~5s         ~26s        ~5s          ~18s         ~5s         ~16s         |
+=============================================================================+
                                                                    |
                                                          [Approval Gate 1]
                                                                    |
PHASE 2: Database Restore (AGNT0007 - DR DB Server)                 v
+=============================================================================+
| DBFile      DBFile      DBFile       DB          DB                         |
| _S3Downld-> _Unzip  --> _Check   --> _Restore--> _Check                     |
|  ~3s         ~3s         ~3s          ~3s         ~3s                       |
+=============================================================================+
                                                                    |
                                                          [Approval Gate 2]
                                                                    |
PHASE 3: File Restore (AGNT0006 - DR App/File Server)              v
+=============================================================================+
| File        File        File         File                                   |
| _S3Downld-> _Unzip  --> _Check   --> _Restore                               |
|  ~5s         ~3s         ~3s          ~3s                                   |
+=============================================================================+
                                                                    |
                                                          [Approval Gate 3]
                                                                    |
PHASE 4: Application Start (AGNT0006 - DR App/File Server)         v
+=============================================================================+
| AP_Start --> AP_Check                                                       |
|  ~23s         ~17s                                                          |
+=============================================================================+
```

#### Phase 1: Infrastructure Startup

Boots all three DR EC2 instances in dependency order (DB -> File -> App) and verifies each reaches `running` state.

| Task | Agent | Command | Wait |
|---|---|---|---|
| **DBServer_Start** | AGNT0005 | `aws ec2 start-instances --instance-ids $WF_DB_INSTANCE_ID` | -- |
| **DBServer_Check** | AGNT0005 | `sleep 25 && aws ec2 describe-instances` (check state=running) | 26s |
| **FileServer_Start** | AGNT0005 | `aws ec2 start-instances --instance-ids $WF_FILE_INSTANCE_ID` | -- |
| **FileServer_Check** | AGNT0005 | `sleep 20 && aws ec2 describe-instances` (check state=running) | 18s |
| **APServer_Start** | AGNT0005 | `aws ec2 start-instances --instance-ids $WF_AP_INSTANCE_ID` | -- |
| **APServer_Check** | AGNT0005 | `sleep 20 && aws ec2 describe-instances` (check state=running) | 16s |

**Approval Gate 1:** Requires sign-off from **Momoyo** or **Fernando** before proceeding to data restore.

#### Phase 2: Database Restore

Downloads the latest DB backup from S3, decompresses, verifies, and imports into MySQL.

| Task | Agent | Command |
|---|---|---|
| **DBFile_S3Downld** | AGNT0007 | `aws s3 cp s3://ccoe-stonebranch-poc-1/testdb_backup.sql_YYYYMMDD.gz /tmp/` |
| **DBFile_Unzip** | AGNT0007 | `gzip -d /tmp/testdb_backup.sql_YYYYMMDD.gz` |
| **DBFile_Check** | AGNT0007 | `[ -f "/tmp/testdb_backup.sql_YYYYMMDD" ]` |
| **DB_Restore** | AGNT0007 | `mysql -u springappuser -p'password' db_example < /tmp/testdb_backup.sql_YYYYMMDD` |
| **DB_Check** | AGNT0007 | `mysql ... -e "SHOW DATABASES LIKE 'db_example';"` |

**Approval Gate 2:** Requires sign-off before restoring files.

#### Phase 3: File Restore

Downloads the latest NFS file backup from S3, decompresses, verifies, and copies to the NFS mount.

| Task | Agent | Command |
|---|---|---|
| **File_S3Downld** | AGNT0006 | `aws s3 cp s3://ccoe-stonebranch-poc-1/testfile1_YYYYMMDD.gz /tmp/` |
| **File_Unzip** | AGNT0006 | `gzip -d /tmp/testfile1_YYYYMMDD.gz` |
| **File_Check** | AGNT0006 | `[ -f "/tmp/testfile1_YYYYMMDD" ]` |
| **File_Restore** | AGNT0006 | `cp -f /tmp/testfile1_YYYYMMDD /mnt/nfs_clientshare/testfile1` |

**Approval Gate 3:** Requires sign-off before starting the application.

#### Phase 4: Application Start

Starts the Spring Boot application and verifies it is running.

| Task | Agent | Command |
|---|---|---|
| **AP_Start** | AGNT0006 | `cd /app/springapp-05/gs-accessing-data-mysql/complete && nohup ./mvnw spring-boot:run &` |
| **AP_Check** | AGNT0006 | `lsof -c java` (verify Java process is running) |

**Runtime:** Average 3 min 30 sec | Max 6 min 5 sec | Min 2 min 41 sec (across 9 runs)

---

## Agent Inventory

| Agent ID | Hostname | Location | Role | Workflows |
|---|---|---|---|---|
| `AGNT0003` | `ccoestonebranchapp2` | CTC On-Premise VM 3 | Production MySQL Server | DBBackupFlow (all 4 tasks) |
| `AGNT0004` | `ccoestonebranchapp3` | CTC On-Premise VM 4 | Production File Server | FileBackupFlow (all 3 tasks) |
| `AGNT0005` | `ip-10-0-1-242` | AWS EC2 | DR Orchestrator | DRFailOverFlow Phase 1 (EC2 start/check) |
| `AGNT0006` | `ip-10-0-1-210` | AWS EC2 | DR App/File Server | DRFailOverFlow Phases 3 & 4 (file restore, app start) |
| `AGNT0007` | `ip-10-0-1-144` | AWS EC2 | DR DB Server | DRFailOverFlow Phase 2 (DB restore) |

---

## Workflow Variables Reference

### Common Variables (used across workflows)

| Variable | Default Value | Used By |
|---|---|---|
| `WF_DB_NAME` | `db_example` | DBBackupFlow, DRFailOverFlow |
| `WF_BACKUP_PATH` | `/tmp/testdb_backup.sql` | DBBackupFlow, DRFailOverFlow |
| `WF_SQLDUMP_PATH` | `/usr/bin/mysqldump` | DBBackupFlow, DRFailOverFlow |
| `WF_MYSQL_PATH` | `/usr/bin/mysql` | DRFailOverFlow |
| `WF_AWS_PATH` | `/usr/local/bin/aws` | All workflows |
| `WF_S3_PATH` | `s3://ccoe-stonebranch-poc-1/` | All workflows |
| `WF_S3_BUCKET` | `ccoe-stonebranch-poc-1` | All workflows |
| `WF_S3_BUCKUP_FILE` | `testdb_backup.sql` | DBBackupFlow, FileBackupFlow |
| `WF_NFSFILE` | `testfile1` | FileBackupFlow, DRFailOverFlow |
| `WF_NFSFILE_PATH` | `/var/nfs/shared_folder/testfile1` | FileBackupFlow, DRFailOverFlow |

### DR-Specific Variables (DRFailOverFlow only)

| Variable | Default Value | Description |
|---|---|---|
| `WF_DB_INSTANCE_ID` | `i-04784d39a4c5c256c` | EC2 instance ID for DR database server |
| `WF_FILE_INSTANCE_ID` | `i-0fdd1546ba7a45cfa` | EC2 instance ID for DR file server |
| `WF_AP_INSTANCE_ID` | `i-0d7ae2f9fb987c75e` | EC2 instance ID for DR application server |
| `WF_DBBACKUP_FILE` | `testdb_backup.sql` | Database backup filename in S3 |
| `WF_TEMP` | `/tmp/` | Temporary directory for downloads |
| `WF_APP_PATH` | `/app/springapp-05/gs-accessing-data-mysql/complete` | Spring Boot application path on DR server |

---

## Runbook: Executing in Stonebranch UAC

### Prerequisites

Before executing any workflow, ensure the following are in place:

1. **Stonebranch OMS** is installed and running on both:
   - CTC on-premise Ubuntu VM (primary OMS + Universal Control Plane)
   - AWS EC2 instance (DR site OMS)

2. **Universal Agents** are installed and registered:
   - Production: VMs for Application, MySQL, and File Server
   - DR: EC2 instances for Orchestrator, DB Server, and App/File Server

3. **AWS CLI** is installed and configured on all agents that interact with S3 or EC2:
   - Production agents: `AGNT0003`, `AGNT0004` (S3 access)
   - DR agents: `AGNT0005` (EC2 management), `AGNT0006` (S3 access), `AGNT0007` (S3 access)

4. **MySQL** is installed on production DB server and DR DB server.

5. **NFS** is configured:
   - Production: NFS server exporting `/var/nfs/shared_folder/`
   - DR: NFS client mount at `/mnt/nfs_clientshare/`

6. **Java 11+** and **Maven** are installed on the Application servers (production and DR).

7. **S3 Bucket** `ccoe-stonebranch-poc-1` exists and is accessible from both sites.

8. **Spring Boot Application** source code is deployed to `/app/springapp-05/gs-accessing-data-mysql/complete` on both the production and DR application servers.

---

### Step 1: Import Task Definitions

Import all workflow and task JSON files into Stonebranch UAC using either the **Web UI** or the **REST API**.

#### Option A: Via Web UI

1. Log in to the Stonebranch UAC Web UI.
2. Navigate to **Automation Center > Tasks > Import**.
3. Import tasks in the following order (tasks must exist before the workflows that reference them):

**Import individual tasks first:**

```
# DBBackupFlow tasks
stonebranch/DBBackupFlow/DBServerBKUP_MAIN.json
stonebranch/DBBackupFlow/DBServerBKUP_ZIP.json
stonebranch/DBBackupFlow/DBServerBKUP_S3UP.json
stonebranch/DBBackupFlow/DBServerBKUP_S3Check.json

# FileBackupFlow tasks
stonebranch/FileBackupFlow/FileServerBKUP_ZIP.json
stonebranch/FileBackupFlow/FileServerBKUP_S3UP.json
stonebranch/FileBackupFlow/FileServerBKUP_S3Check.json

# DRFailOverFlow tasks
stonebranch/DRFailOverFlow/DBServer_Start.json
stonebranch/DRFailOverFlow/DBServer_Check.json
stonebranch/DRFailOverFlow/FileServer_Start.json
stonebranch/DRFailOverFlow/FileServer_Check.json
stonebranch/DRFailOverFlow/APServer_Start.json
stonebranch/DRFailOverFlow/APServer_Check.json
stonebranch/DRFailOverFlow/DBFile_S3Downld.json
stonebranch/DRFailOverFlow/DBFile_Unzip.json
stonebranch/DRFailOverFlow/DBFile_Check.json
stonebranch/DRFailOverFlow/DB_Restore.json
stonebranch/DRFailOverFlow/DB_Check.json
stonebranch/DRFailOverFlow/File_S3Downld.json
stonebranch/DRFailOverFlow/File_Unzip.json
stonebranch/DRFailOverFlow/File_Check.json
stonebranch/DRFailOverFlow/File_Restore.json
stonebranch/DRFailOverFlow/AP_Start.json
stonebranch/DRFailOverFlow/AP_Check.json
stonebranch/DRFailOverFlow/ApprovalTask1.json
```

**Then import the workflow definitions:**

```
stonebranch/DBBackupFlow/DBBackupFlow.json
stonebranch/FileBackupFlow/FileBackupFlow.json
stonebranch/DRFailOverFlow/DRFailOverFlow.json
```

#### Option B: Via REST API

```bash
# Set your OMS URL and credentials
OMS_URL="https://<oms-host>:<port>/oms"
AUTH="Basic <base64-encoded-credentials>"

# Import a task definition
curl -X POST "$OMS_URL/api/task" \
  -H "Authorization: $AUTH" \
  -H "Content-Type: application/json" \
  -d @stonebranch/DBBackupFlow/DBServerBKUP_MAIN.json

# Repeat for each task file, then import workflow definitions
curl -X POST "$OMS_URL/api/task" \
  -H "Authorization: $AUTH" \
  -H "Content-Type: application/json" \
  -d @stonebranch/DBBackupFlow/DBBackupFlow.json
```

---

### Step 2: Configure Credentials

1. Navigate to **Security > Credentials** in the UAC Web UI.
2. Create the credential `cred_ccoeadmin`:
   - **Name:** `cred_ccoeadmin`
   - **Runtime User:** The OS user that has permissions to run `mysqldump`, `mysql`, `gzip`, `aws`, and manage NFS files on the respective servers.
   - **Password:** The OS user's password.
3. Assign this credential to all Unix tasks (it is already referenced in the task JSON definitions).

---

### Step 3: Verify Agent Connectivity

1. Navigate to **Agents > Agent List** in the UAC Web UI.
2. Verify all 5 agents show **Active** status:

| Agent | Expected Status |
|---|---|
| `ccoestonebranchapp2 - AGNT0003` | Active (Production DB Server) |
| `ccoestonebranchapp3 - AGNT0004` | Active (Production File Server) |
| `ip-10-0-1-242 - AGNT0005` | Active (DR Orchestrator) |
| `ip-10-0-1-210 - AGNT0006` | Active (DR App/File Server) |
| `ip-10-0-1-144 - AGNT0007` | Active (DR DB Server) |

3. If any agent shows **Offline**, SSH into the corresponding server and restart the agent:
   ```bash
   sudo /etc/init.d/ubtagent restart
   ```

> **Note:** DR agents (`AGNT0005`, `AGNT0006`, `AGNT0007`) may be offline if the DR EC2 instances are stopped. The `AGNT0005` (Orchestrator) must always be running on an always-on EC2 instance so it can start the other DR instances during failover.

---

### Step 4: Configure Workflow Variables

1. Navigate to **Automation Center > Tasks > Workflows**.
2. Open each workflow and verify the variables match your environment.
3. **Critical variables to update for your environment:**

| Variable | What To Change |
|---|---|
| `WF_DB_INSTANCE_ID` | Your DR database EC2 instance ID |
| `WF_FILE_INSTANCE_ID` | Your DR file server EC2 instance ID |
| `WF_AP_INSTANCE_ID` | Your DR application server EC2 instance ID |
| `WF_S3_BUCKET` / `WF_S3_PATH` | Your S3 bucket name and URI |
| `WF_APP_PATH` | Path to your Spring Boot application on the DR server |
| `WF_DB_NAME` | Your MySQL database name |
| `WF_NFSFILE_PATH` | Path to your NFS file on the production server |
| `WF_NFSFILE` | Filename of the NFS file to back up |

---

### Step 5: Schedule Daily Backups

#### Schedule DBBackupFlow

1. Navigate to **Automation Center > Tasks > Workflows > DBBackupFlow**.
2. Click **Triggers** tab.
3. Add a **Time Trigger**:
   - **Schedule:** Daily
   - **Time:** `08:00` (or your preferred backup time)
   - **Time Zone:** Your local timezone (e.g., `Asia/Singapore` for UTC+8)
4. Save and enable the trigger.

#### Schedule FileBackupFlow

1. Navigate to **Automation Center > Tasks > Workflows > FileBackupFlow**.
2. Click **Triggers** tab.
3. Add a **Time Trigger**:
   - **Schedule:** Daily
   - **Time:** `08:00` (same time as DB backup, they run on different agents)
4. Save and enable the trigger.

#### Verify Backups Are Running

1. Navigate to **Activity > Activity Monitor**.
2. After the scheduled time, verify both workflows completed with **Success** status.
3. Verify S3 contains the expected files:
   ```bash
   aws s3 ls s3://ccoe-stonebranch-poc-1/
   # Expected output:
   # testdb_backup.sql_YYYYMMDD.gz
   # testfile1_YYYYMMDD.gz
   ```

---

### Step 6: Execute DR Failover

> **IMPORTANT:** Only execute the DR failover workflow when a genuine disaster or DR test has been declared.

#### 6.1 Launch the Failover

1. Navigate to **Automation Center > Tasks > Workflows > DRFailOverFlow**.
2. Click **Launch Task** (or right-click > **Launch**).
3. Optionally review/modify variables before launch (e.g., update EC2 instance IDs if they have changed).
4. Click **Submit**.

#### 6.2 Monitor Phase 1 -- Infrastructure Startup

1. Open **Activity > Activity Monitor** and find the running `DRFailOverFlow` instance.
2. Click on the workflow instance to see the visual workflow graph.
3. Watch as the 6 Phase 1 tasks execute sequentially:
   - `DBServer_Start` -> `DBServer_Check` -> `FileServer_Start` -> `FileServer_Check` -> `APServer_Start` -> `APServer_Check`
4. Each `_Check` task includes a 20-30 second sleep to allow EC2 instances to fully boot.

#### 6.3 Approve Gate 1

1. The workflow will pause at **ApprovalTask1** (vertex 46).
2. Navigate to **Activity > Approval Tasks** (or check your email/notification if configured).
3. Review the approval request -- confirm all 3 EC2 instances are running.
4. **Approve** the task (requires either `Momoyo` or `Fernando`).

#### 6.4 Monitor Phase 2 -- Database Restore

1. After approval, Phase 2 begins automatically.
2. Watch tasks: `DBFile_S3Downld` -> `DBFile_Unzip` -> `DBFile_Check` -> `DB_Restore` -> `DB_Check`.
3. The `DB_Check` task verifies the database exists after import.

#### 6.5 Approve Gate 2

1. The workflow pauses at **ApprovalTask1** (vertex 47).
2. Verify the database was restored successfully (check `DB_Check` task output).
3. **Approve** to proceed to file restore.

#### 6.6 Monitor Phase 3 -- File Restore

1. After approval, Phase 3 begins.
2. Watch tasks: `File_S3Downld` -> `File_Unzip` -> `File_Check` -> `File_Restore`.
3. The `File_Restore` task copies the decompressed file to `/mnt/nfs_clientshare/testfile1`.

#### 6.7 Approve Gate 3

1. The workflow pauses at **ApprovalTask1** (vertex 48).
2. Verify the file was restored (check `File_Restore` task output).
3. **Approve** to proceed to application startup.

#### 6.8 Monitor Phase 4 -- Application Start

1. After approval, Phase 4 begins.
2. `AP_Start` runs the Spring Boot application via `nohup ./mvnw spring-boot:run &`.
3. `AP_Check` verifies a Java process is running via `lsof -c java`.
4. Once `AP_Check` succeeds, the workflow completes with **Success**.

---

### Step 7: Post-Failover Verification

After the workflow completes successfully:

1. **Verify the application is accessible:**
   ```bash
   curl http://<DR-App-Server-IP>:8080/api/users
   ```

2. **Verify database data was restored:**
   ```bash
   curl http://<DR-App-Server-IP>:8080/api/users
   # Should return the user records from the latest backup
   ```

3. **Verify file data was restored:**
   ```bash
   curl http://<DR-App-Server-IP>:8080/api/file/testfile1
   # Should return the content of the restored NFS file
   ```

4. **Check Swagger UI for full API exploration:**
   ```
   http://<DR-App-Server-IP>:8080/swagger-ui.html
   ```

5. **Update DNS / Load Balancer** to point to the DR application server if applicable.

---

## Repository Structure

```
stonebranch.demo/
  README.md                              # This file
  app/                                   # Spring Boot application source code
    src/main/java/.../
      AccessingDataMysqlApplication.java # Entry point
      MainController.java               # Legacy combined endpoint
      User.java                         # JPA entity
      UserRepository.java               # Spring Data repository
      UserController.java               # REST /api/users CRUD
      FileController.java               # REST /api/file operations
    src/main/resources/
      application.properties             # DB connection config
    pom.xml                              # Maven build file
    run-app.sh                           # App runner script
    check-java.sh                        # Java version checker
    compose.yml                          # Docker Compose (dev MySQL)
  stonebranch/                           # Stonebranch UAC config-as-code
    DBBackupFlow/                        # Daily database backup workflow
      DBBackupFlow.json                  # Workflow definition
      DBServerBKUP_MAIN.json            # Task: mysqldump
      DBServerBKUP_ZIP.json             # Task: gzip compress
      DBServerBKUP_S3UP.json            # Task: S3 upload
      DBServerBKUP_S3Check.json         # Task: S3 verification
      README.md                          # Detailed workflow documentation
    FileBackupFlow/                      # Daily file backup workflow
      FileBackupFlow.json                # Workflow definition
      FileServerBKUP_ZIP.json           # Task: gzip compress
      FileServerBKUP_S3UP.json          # Task: S3 upload
      FileServerBKUP_S3Check.json       # Task: S3 verification
      README.md                          # Detailed workflow documentation
    DRFailOverFlow/                      # DR failover workflow
      DRFailOverFlow.json                # Workflow definition (20 tasks, 4 phases)
      DBServer_Start.json               # Phase 1: Start DB EC2
      DBServer_Check.json               # Phase 1: Verify DB EC2
      FileServer_Start.json             # Phase 1: Start File EC2
      FileServer_Check.json             # Phase 1: Verify File EC2
      APServer_Start.json               # Phase 1: Start App EC2
      APServer_Check.json               # Phase 1: Verify App EC2
      DBFile_S3Downld.json              # Phase 2: Download DB backup
      DBFile_Unzip.json                 # Phase 2: Decompress DB backup
      DBFile_Check.json                 # Phase 2: Verify DB file
      DB_Restore.json                   # Phase 2: MySQL import
      DB_Check.json                     # Phase 2: Verify database
      File_S3Downld.json                # Phase 3: Download file backup
      File_Unzip.json                   # Phase 3: Decompress file backup
      File_Check.json                   # Phase 3: Verify file
      File_Restore.json                 # Phase 3: Copy to NFS mount
      AP_Start.json                     # Phase 4: Start Spring Boot
      AP_Check.json                     # Phase 4: Verify Java process
      ApprovalTask1.json                # Approval gate (used 3x)
      README.md                          # Detailed workflow documentation
```
