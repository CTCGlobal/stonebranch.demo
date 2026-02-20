# DBBackupFlow - Database Backup Workflow

## Overview

**DBBackupFlow** is a Stonebranch Universal Automation Center (UAC) workflow that automates the end-to-end process of backing up a MySQL database, compressing the backup, uploading it to Amazon S3, and verifying the upload. The workflow is defined as config-as-code in JSON format and consists of 4 sequential Unix tasks.

## Workflow Architecture

```
+-----------------+     +------------------+     +------------------+     +--------------------+
| DBServerBKUP    | --> | DBServerBKUP     | --> | DBServerBKUP     | --> | DBServerBKUP       |
| _MAIN           |     | _ZIP             |     | _S3UP            |     | _S3Check           |
| (MySQL Dump)    |     | (Gzip Compress)  |     | (S3 Upload)      |     | (S3 Verification)  |
+-----------------+     +------------------+     +------------------+     +--------------------+
      ~3s                      ~3s                     ~11s                      ~7s
```

Each transition between tasks requires a **Success** exit condition -- if any task fails, the workflow stops and does not proceed to the next step.

## Workflow Variables

The workflow uses the following variables, defined at the workflow level and inherited by all tasks:

| Variable | Default Value | Description |
|---|---|---|
| `WF_DB_NAME` | `db_example` | Name of the MySQL database to back up |
| `WF_BACKUP_PATH` | `/tmp/testdb_backup.sql` | Local file path for the SQL dump output |
| `WF_SQLDUMP_PATH` | `/usr/bin/mysqldump` | Path to the `mysqldump` binary |
| `WF_AWS_PATH` | `/usr/local/bin/aws` | Path to the AWS CLI binary |
| `WF_S3_PATH` | `s3://ccoe-stonebranch-poc-1/` | S3 destination URI for the upload |
| `WF_S3_BUCKET` | `ccoe-stonebranch-poc-1` | S3 bucket name (used for verification) |
| `WF_S3_BUCKUP_FILE` | `testdb_backup.sql` | Base filename used for S3 object key verification |
| `WFVL1` | `/tmp` | General-purpose path variable |
| `WFVL2` | `date` | General-purpose variable |

## Task Details

### 1. DBServerBKUP_MAIN - Database Dump

- **Type:** Unix task
- **Agent:** `ccoestonebranchapp2 - AGNT0003`
- **Credentials:** `cred_ccoeadmin`
- **Runtime Directory:** `/app`
- **Avg Runtime:** ~3 seconds

**What it does:** Executes `mysqldump` to export the specified MySQL database to a SQL file.

```bash
mysqldump -u springappuser -p'password' $WF_DB_NAME > $WF_BACKUP_PATH
```

The resulting file is written to the path specified by `WF_BACKUP_PATH` (default: `/tmp/testdb_backup.sql`).

### 2. DBServerBKUP_ZIP - Compress Backup

- **Type:** Unix task
- **Agent:** `ccoestonebranchapp2 - AGNT0003`
- **Credentials:** `cred_ccoeadmin`
- **Runtime Directory:** `/app`
- **Avg Runtime:** ~3 seconds

**What it does:** Compresses the SQL dump file using `gzip`, appending the current date (`YYYYMMDD`) to the filename.

```bash
DATE=$(date +'%Y%m%d')
gzip -c $WF_BACKUP_PATH > ${WF_BACKUP_PATH}_${DATE}.gz
```

This produces a date-stamped compressed file, e.g., `/tmp/testdb_backup.sql_20260220.gz`.

### 3. DBServerBKUP_S3UP - Upload to S3

- **Type:** Unix task
- **Agent:** `ccoestonebranchapp2 - AGNT0003`
- **Credentials:** `cred_ccoeadmin`
- **Runtime Directory:** `/app`
- **Avg Runtime:** ~11 seconds

**What it does:** Uploads the compressed backup file to the configured S3 bucket using the AWS CLI.

```bash
DATE=$(date +'%Y%m%d')
aws s3 cp ${WF_BACKUP_PATH}_${DATE}.gz $WF_S3_PATH
```

### 4. DBServerBKUP_S3Check - Verify Upload

- **Type:** Unix task
- **Agent:** `ccoestonebranchapp2 - AGNT0003`
- **Credentials:** `cred_ccoeadmin`
- **Runtime Directory:** `/app`
- **Avg Runtime:** ~7 seconds

**What it does:** Verifies that the uploaded file exists in S3 using `s3api head-object`. If the file is not found, the task exits with code 1 (failure).

```bash
DATE=$(date +'%Y%m%d')
if aws s3api head-object --bucket $WF_S3_BUCKET --key ${WF_S3_BUCKUP_FILE}_${DATE}.gz > /dev/null 2>&1; then
    echo "File exists"
else
    echo "File not exists"
    exit 1
fi
```

## Execution Details

| Property | Value |
|---|---|
| **Total Tasks** | 4 |
| **Avg Total Runtime** | ~40 seconds |
| **Max Observed Runtime** | 7 min 50 sec |
| **Total Runs** | 24 |
| **First Run** | 2026-02-09 16:50 (UTC+8) |
| **Last Run** | 2026-02-20 08:00 (UTC+8) |
| **Exit Code Policy** | Success on exit code `0` (all tasks) |

## Design Characteristics

- **Sequential execution**: All 4 tasks run in strict linear order with success-gated transitions, ensuring each stage completes before the next begins.
- **Single agent**: All tasks execute on the same agent (`ccoestonebranchapp2`), meaning the database server has both local filesystem access (for the dump/compress) and AWS CLI access (for S3 operations).
- **Date-stamped backups**: The compress, upload, and verify steps all append `YYYYMMDD` to the filename, producing a unique backup per day.
- **Verification step**: The final task acts as a post-condition check, confirming the backup actually landed in S3 before the workflow reports success.
- **Centralized variables**: All configurable paths (database name, backup path, S3 destination, tool paths) are defined as workflow-level variables, making the workflow reusable across environments without modifying individual tasks.
- **Credential management**: All tasks use the `cred_ccoeadmin` credential stored in Stonebranch, avoiding hardcoded credentials in the workflow definition (note: the `mysqldump` password in the MAIN task command is an exception).

## File Structure

```
DBBackupFlow/
  DBBackupFlow.json        # Workflow definition (edges, vertices, variables)
  DBServerBKUP_MAIN.json   # Task 1 - MySQL dump
  DBServerBKUP_ZIP.json    # Task 2 - Gzip compression
  DBServerBKUP_S3UP.json   # Task 3 - S3 upload
  DBServerBKUP_S3Check.json # Task 4 - S3 verification
```
