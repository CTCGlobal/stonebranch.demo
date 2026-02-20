# FileBackupFlow - File Server Backup Workflow

## Overview

**FileBackupFlow** is a Stonebranch Universal Automation Center (UAC) workflow that automates the backup of files from an NFS shared folder to Amazon S3. The workflow compresses the target file, uploads it to S3, and verifies the upload succeeded. It is defined as config-as-code in JSON format and consists of 3 sequential Unix tasks.

## Workflow Architecture

```
+------------------+     +-------------------+     +---------------------+
| FileServerBKUP   | --> | FileServerBKUP    | --> | FileServerBKUP      |
| _ZIP             |     | _S3UP             |     | _S3Check            |
| (Gzip Compress)  |     | (S3 Upload)       |     | (S3 Verification)   |
+------------------+     +-------------------+     +---------------------+
       ~3s                       ~11s                       ~7s
```

Each transition between tasks requires a **Success** exit condition -- if any task fails, the workflow stops and does not proceed to the next step.

## Workflow Variables

The workflow uses the following variables, defined at the workflow level and inherited by all tasks:

| Variable | Default Value | Description |
|---|---|---|
| `WF_NFSFILE_PATH` | `/var/nfs/shared_folder/testfile1` | Full path to the NFS file to back up |
| `WF_NFSFILE` | `testfile1` | Base filename of the NFS file (used as S3 object key) |
| `WF_AWS_PATH` | `/usr/local/bin/aws` | Path to the AWS CLI binary |
| `WF_S3_PATH` | `s3://ccoe-stonebranch-poc-1/` | S3 destination URI for the upload |
| `WF_S3_BUCKET` | `ccoe-stonebranch-poc-1` | S3 bucket name (used for verification) |
| `WF_BACKUP_PATH` | `/tmp/testdb_backup.sql` | General backup path variable (inherited from template) |
| `WF_DB_NAME` | `db_example` | Database name variable (inherited from template) |
| `WF_SQLDUMP_PATH` | `/usr/bin/mysqldump` | SQL dump path variable (inherited from template) |
| `WF_S3_BUCKUP_FILE` | `testdb_backup.sql` | Backup file variable (inherited from template) |
| `WFVL1` | `/tmp` | General-purpose path variable |
| `WFVL2` | `date` | General-purpose variable |

> **Note:** Several variables (`WF_DB_NAME`, `WF_BACKUP_PATH`, `WF_SQLDUMP_PATH`, `WF_S3_BUCKUP_FILE`) appear inherited from a shared workflow template and are not actively used by the file backup tasks. The key variables for this workflow are `WF_NFSFILE_PATH`, `WF_NFSFILE`, `WF_AWS_PATH`, `WF_S3_PATH`, and `WF_S3_BUCKET`.

## Task Details

### 1. FileServerBKUP_ZIP - Compress File

- **Type:** Unix task
- **Agent:** `ccoestonebranchapp3 - AGNT0004`
- **Credentials:** `cred_ccoeadmin`
- **Runtime Directory:** `/app`
- **Avg Runtime:** ~3 seconds

**What it does:** Compresses the NFS file using `gzip`, appending the current date (`YYYYMMDD`) to the filename.

```bash
DATE=$(date +'%Y%m%d')
gzip -c $WF_NFSFILE_PATH > ${WF_NFSFILE_PATH}_${DATE}.gz
```

This produces a date-stamped compressed file, e.g., `/var/nfs/shared_folder/testfile1_20260220.gz`.

### 2. FileServerBKUP_S3UP - Upload to S3

- **Type:** Unix task
- **Agent:** `ccoestonebranchapp3 - AGNT0004`
- **Credentials:** `cred_ccoeadmin`
- **Runtime Directory:** `/app`
- **Avg Runtime:** ~11 seconds

**What it does:** Uploads the compressed file to the configured S3 bucket using the AWS CLI.

```bash
DATE=$(date +'%Y%m%d')
aws s3 cp ${WF_NFSFILE_PATH}_${DATE}.gz $WF_S3_PATH
```

### 3. FileServerBKUP_S3Check - Verify Upload

- **Type:** Unix task
- **Agent:** `ccoestonebranchapp3 - AGNT0004`
- **Credentials:** `cred_ccoeadmin`
- **Runtime Directory:** `/app`
- **Avg Runtime:** ~7 seconds

**What it does:** Verifies that the uploaded file exists in S3 using `s3api head-object`. If the file is not found, the task exits with code 1 (failure).

```bash
DATE=$(date +'%Y%m%d')
if aws s3api head-object --bucket $WF_S3_BUCKET --key ${WF_NFSFILE}_${DATE}.gz > /dev/null 2>&1; then
    echo "File exists"
else
    echo "File not exists"
    exit 1
fi
```

## Execution Details

| Property | Value |
|---|---|
| **Total Tasks** | 3 |
| **Avg Total Runtime** | ~21 seconds |
| **Max Observed Runtime** | 21 seconds |
| **Total Runs** | 20 |
| **First Run** | 2026-02-09 19:35 (UTC+8) |
| **Last Run** | 2026-02-20 08:00 (UTC+8) |
| **Exit Code Policy** | Success on exit code `0` (all tasks) |

## Design Characteristics

- **Sequential execution**: All 3 tasks run in strict linear order with success-gated transitions, ensuring each stage completes before the next begins.
- **NFS-based source**: Unlike the DBBackupFlow (which dumps a database), this workflow backs up files directly from an NFS shared mount, making it suitable for file server disaster recovery.
- **Dedicated agent**: All tasks execute on `ccoestonebranchapp3 - AGNT0004`, a separate agent from the database backup workflow. This agent has access to both the NFS mount and the AWS CLI.
- **Date-stamped backups**: The compress, upload, and verify steps all append `YYYYMMDD` to the filename, producing a unique backup per day.
- **Verification step**: The final task acts as a post-condition check, confirming the backup file exists in S3 before the workflow reports success. On failure, it prints the expected bucket/key for debugging.
- **Centralized variables**: All configurable paths (NFS file path, S3 destination, tool paths) are defined as workflow-level variables, making the workflow reusable across environments without modifying individual tasks.
- **Credential management**: All tasks use the `cred_ccoeadmin` credential stored in Stonebranch for secure execution.

## Comparison with DBBackupFlow

| Aspect | DBBackupFlow | FileBackupFlow |
|---|---|---|
| **Source** | MySQL database dump | NFS shared folder file |
| **Tasks** | 4 (dump, zip, upload, verify) | 3 (zip, upload, verify) |
| **Agent** | `ccoestonebranchapp2 - AGNT0003` | `ccoestonebranchapp3 - AGNT0004` |
| **Avg Runtime** | ~40 seconds | ~21 seconds |
| **Key Variable** | `WF_DB_NAME`, `WF_BACKUP_PATH` | `WF_NFSFILE_PATH`, `WF_NFSFILE` |

## File Structure

```
FileBackupFlow/
  FileBackupFlow.json        # Workflow definition (edges, vertices, variables)
  FileServerBKUP_ZIP.json    # Task 1 - Gzip compression
  FileServerBKUP_S3UP.json   # Task 2 - S3 upload
  FileServerBKUP_S3Check.json # Task 3 - S3 verification
```
