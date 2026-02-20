# DRFailOverFlow - Disaster Recovery Failover Workflow

## Overview

**DRFailOverFlow** is a Stonebranch Universal Automation Center (UAC) workflow that orchestrates a full disaster recovery (DR) failover procedure. It boots up standby EC2 instances, restores database and file data from S3 backups, and starts the application on the DR environment. The workflow includes **manual approval gates** between each major phase, ensuring human oversight at every critical transition. It is defined as config-as-code in JSON format and consists of 20 task instances across 4 phases.

## Workflow Architecture

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

Each transition between tasks requires a **Success** exit condition. The three approval gates use the same `ApprovalTask1` definition (placed as 3 separate vertices in the workflow) and require sign-off from either **Momoyo** or **Fernando** before proceeding.

## Workflow Variables

| Variable | Default Value | Description |
|---|---|---|
| `WF_DB_INSTANCE_ID` | `i-04784d39a4c5c256c` | EC2 instance ID for the DR database server |
| `WF_FILE_INSTANCE_ID` | `i-0fdd1546ba7a45cfa` | EC2 instance ID for the DR file server |
| `WF_AP_INSTANCE_ID` | `i-0d7ae2f9fb987c75e` | EC2 instance ID for the DR application server |
| `WF_AWS_PATH` | `/usr/local/bin/aws` | Path to the AWS CLI binary |
| `WF_S3_PATH` | `s3://ccoe-stonebranch-poc-1/` | S3 bucket URI containing backups |
| `WF_S3_BUCKET` | `ccoe-stonebranch-poc-1` | S3 bucket name |
| `WF_TEMP` | `/tmp/` | Temporary directory for downloaded files |
| `WF_DB_NAME` | `db_example` | MySQL database name to restore |
| `WF_DBBACKUP_FILE` | `testdb_backup.sql` | Database backup filename in S3 |
| `WF_BACKUP_PATH` | `/tmp/testdb_backup.sql` | Local path for DB backup |
| `WF_SQLDUMP_PATH` | `/usr/bin/mysqldump` | Path to mysqldump binary |
| `WF_MYSQL_PATH` | `/usr/bin/mysql` | Path to mysql client binary |
| `WF_NFSFILE` | `testfile1` | NFS backup filename in S3 |
| `WF_NFSFILE_PATH` | `/var/nfs/shared_folder/testfile1` | NFS file path |
| `WF_S3_BUCKUP_FILE` | `testdb_backup.sql` | S3 backup file key |
| `WF_APP_PATH` | `/app/springapp-05/gs-accessing-data-mysql/complete` | Spring Boot application path |
| `WF_DB_INSTANCE` | `i-04784d39a4c5c256c` | DB instance reference (legacy) |

## Phase Details

### Phase 1: Infrastructure Startup

Boots up all three DR EC2 instances sequentially (DB server first, then file server, then app server) and verifies each is in `running` state before proceeding to the next.

**Agent:** `ip-10-0-1-242 - AGNT0005` (DR Orchestrator)

| Task | Action | Command | Avg Runtime |
|---|---|---|---|
| **DBServer_Start** | Start DB EC2 instance | `aws ec2 start-instances --instance-ids $WF_DB_INSTANCE_ID` | ~5s |
| **DBServer_Check** | Verify DB instance is running | `aws ec2 describe-instances` + check state = `running` | ~26s |
| **FileServer_Start** | Start File EC2 instance | `aws ec2 start-instances --instance-ids $WF_FILE_INSTANCE_ID` | ~5s |
| **FileServer_Check** | Verify File instance is running | `aws ec2 describe-instances` + check state = `running` | ~18s |
| **APServer_Start** | Start App EC2 instance | `aws ec2 start-instances --instance-ids $WF_AP_INSTANCE_ID` | ~5s |
| **APServer_Check** | Verify App instance is running | `aws ec2 describe-instances` + check state = `running` | ~16s |

The `_Check` tasks include a `sleep 20-30` delay to allow EC2 instances time to reach the `running` state before verifying.

### Approval Gate 1

After all three EC2 instances are confirmed running, the workflow pauses for manual approval before restoring data.

### Phase 2: Database Restore

Downloads the database backup from S3, decompresses it, verifies the file, restores it into MySQL, and confirms the database exists.

**Agent:** `ip-10-0-1-144 - AGNT0007` (DR Database Server)

| Task | Action | Command | Avg Runtime |
|---|---|---|---|
| **DBFile_S3Downld** | Download DB backup from S3 | `aws s3 cp ${WF_S3_PATH}${WF_DBBACKUP_FILE}_$DATE.gz /tmp/` | ~3s |
| **DBFile_Unzip** | Decompress backup | `gzip -d /tmp/${WF_DBBACKUP_FILE}_$DATE.gz` | ~3s |
| **DBFile_Check** | Verify file exists locally | `[ -f "/tmp/${WF_DBBACKUP_FILE}_$DATE" ]` | ~3s |
| **DB_Restore** | Import SQL into MySQL | `mysql -u springappuser -p'password' $WF_DB_NAME < /tmp/${WF_DBBACKUP_FILE}_$DATE` | ~3s |
| **DB_Check** | Verify database exists | `mysql ... -e "SHOW DATABASES LIKE '$WF_DB_NAME';"` | ~3s |

### Approval Gate 2

After the database is confirmed restored, the workflow pauses for manual approval before restoring files.

### Phase 3: File Restore

Downloads the file backup from S3, decompresses it, verifies, and copies it to the NFS mount on the DR server.

**Agent:** `ip-10-0-1-210 - AGNT0006` (DR Application/File Server)

| Task | Action | Command | Avg Runtime |
|---|---|---|---|
| **File_S3Downld** | Download file backup from S3 | `aws s3 cp ${WF_S3_PATH}${WF_NFSFILE}_$DATE.gz /tmp/` | ~5s |
| **File_Unzip** | Decompress backup | `gzip -d /tmp/${WF_NFSFILE}_$DATE.gz` | ~3s |
| **File_Check** | Verify file exists locally | `[ -f "/tmp/${WF_NFSFILE}_$DATE" ]` | ~3s |
| **File_Restore** | Copy to NFS mount | `cp -f /tmp/${WF_NFSFILE}_$DATE /mnt/nfs_clientshare/testfile1` | ~3s |

### Approval Gate 3

After the file is confirmed restored, the workflow pauses for manual approval before starting the application.

### Phase 4: Application Start

Starts the Spring Boot application and verifies it is running by checking for Java processes.

**Agent:** `ip-10-0-1-210 - AGNT0006` (DR Application/File Server)

| Task | Action | Command | Avg Runtime |
|---|---|---|---|
| **AP_Start** | Launch Spring Boot app | `cd $WF_APP_PATH && nohup ./mvnw spring-boot:run &` | ~23s |
| **AP_Check** | Verify Java process running | `lsof -c java` check | ~17s |

## Approval Task

**ApprovalTask1** is of type `taskApproval` and is reused 3 times across the workflow (as 3 separate vertices). It requires approval from **either**:

- **Momoyo**
- **Fernando**

The logical operator is **Or**, meaning only one approver is needed to proceed.

## Agents Summary

| Agent | Hostname | Role | Tasks Executed |
|---|---|---|---|
| `AGNT0005` | `ip-10-0-1-242` | DR Orchestrator | EC2 start/check for all 3 servers (Phase 1) |
| `AGNT0007` | `ip-10-0-1-144` | DR Database Server | S3 download, unzip, DB restore, DB check (Phase 2) |
| `AGNT0006` | `ip-10-0-1-210` | DR App/File Server | S3 download, unzip, file restore, app start/check (Phases 3 & 4) |

## Execution Details

| Property | Value |
|---|---|
| **Total Task Instances** | 20 (17 unique tasks + ApprovalTask1 x3) |
| **Avg Total Runtime** | ~3 min 30 sec |
| **Max Observed Runtime** | 6 min 5 sec |
| **Min Observed Runtime** | 2 min 41 sec |
| **Total Runs** | 9 |
| **First Run** | 2026-02-10 12:10 (UTC+8) |
| **Last Run** | 2026-02-12 12:02 (UTC+8) |
| **Exit Code Policy** | Success on exit code `0` (all Unix tasks) |

## Design Characteristics

- **Phased execution with approval gates**: The workflow is divided into 4 distinct phases separated by 3 manual approval gates, ensuring human validation at each critical DR milestone (infrastructure up, DB restored, files restored).
- **Sequential server startup order**: EC2 instances are started in dependency order -- database first, file server second, application server third -- ensuring backend services are available before the application starts.
- **Multi-agent architecture**: Three different agents handle tasks on their respective DR servers, with a dedicated orchestrator agent (`AGNT0005`) managing EC2 lifecycle operations via the AWS CLI.
- **Built-in startup delays**: Check tasks include `sleep 20-30` to allow EC2 instances and services sufficient time to initialize before state verification.
- **Date-stamped restore**: All S3 downloads reference the current date (`YYYYMMDD`), restoring the most recent daily backup produced by the companion DBBackupFlow and FileBackupFlow workflows.
- **Verification at every step**: Each restore sub-chain includes explicit file existence checks and database validation before proceeding, preventing silent failures.
- **Spring Boot application recovery**: The final phase starts the application using Maven wrapper (`mvnw spring-boot:run`) in the background via `nohup`, with process verification using `lsof`.
- **Centralized variables**: All EC2 instance IDs, S3 paths, database names, and tool paths are defined as workflow-level variables, making the DR procedure adaptable to different environments.

## Relationship to Backup Workflows

This workflow is the **restore counterpart** to the daily backup workflows:

| Backup Workflow | DR Restore Phase | What it restores |
|---|---|---|
| **DBBackupFlow** | Phase 2 (DB Restore) | MySQL database from `testdb_backup.sql_YYYYMMDD.gz` |
| **FileBackupFlow** | Phase 3 (File Restore) | NFS file from `testfile1_YYYYMMDD.gz` |

## File Structure

```
DRFailOverFlow/
  DRFailOverFlow.json      # Workflow definition (edges, vertices, variables)
  DBServer_Start.json      # Phase 1 - Start DB EC2 instance
  DBServer_Check.json      # Phase 1 - Verify DB EC2 running
  FileServer_Start.json    # Phase 1 - Start File EC2 instance
  FileServer_Check.json    # Phase 1 - Verify File EC2 running
  APServer_Start.json      # Phase 1 - Start App EC2 instance
  APServer_Check.json      # Phase 1 - Verify App EC2 running
  DBFile_S3Downld.json     # Phase 2 - Download DB backup from S3
  DBFile_Unzip.json        # Phase 2 - Decompress DB backup
  DBFile_Check.json        # Phase 2 - Verify DB file exists
  DB_Restore.json          # Phase 2 - Import SQL into MySQL
  DB_Check.json            # Phase 2 - Verify database exists
  File_S3Downld.json       # Phase 3 - Download file backup from S3
  File_Unzip.json          # Phase 3 - Decompress file backup
  File_Check.json          # Phase 3 - Verify file exists
  File_Restore.json        # Phase 3 - Copy to NFS mount
  AP_Start.json            # Phase 4 - Start Spring Boot application
  AP_Check.json            # Phase 4 - Verify Java process running
  ApprovalTask1.json       # Approval gate (used 3 times in workflow)
```
