# AWS setup (S3 guest wait board)

Turn runs in the restaurant. AWS holds one S3 bucket that serves the guest page and the live wait board to guests' phones.

## 1. Create the bucket

1. In the S3 console, choose **Create bucket**.
2. Set these options:
   - **Name:** for example `turn-wait-<yourname>`
   - **Region:** `us-east-1`, or set `turn.aws.region` to match
3. Under **Block Public Access**, untick **Block all public access** and confirm. Only `public/*` is opened in the next step.
4. Choose **Create**.
5. In the new bucket, go to **Permissions → Bucket policy** and paste this (use your bucket name):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "PublicGuestBoard",
    "Effect": "Allow",
    "Principal": "*",
    "Action": "s3:GetObject",
    "Resource": "arn:aws:s3:::turn-wait-<yourname>/public/*"
  }]
}
```

`shifts/` stays private.

## 2. Give Turn a key that can only write to that bucket

1. In IAM, create a user called `turn-publisher`.
2. Attach this inline policy:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": "s3:PutObject",
    "Resource": "arn:aws:s3:::turn-wait-<yourname>/*"
  }]
}
```

3. Create an access key for the user.
4. Put the key in the environment of the shell that runs Turn. Never put it in `application.yml` or commit it.

```
set AWS_ACCESS_KEY_ID=...
set AWS_SECRET_ACCESS_KEY=...
```

## 3. Run Turn with the bucket

```
mvnw spring-boot:run -Dspring-boot.run.profiles=dining -Dspring-boot.run.arguments="--turn.aws.bucket=turn-wait-<yourname> --server.port=8097"
```

The log should say `Cloud sync on: s3://turn-wait-<yourname>/public/ every 10 s`.

The guest page is then live at:

```
https://turn-wait-<yourname>.s3.amazonaws.com/public/index.html
```

You can make it a QR code on the host stand.

## What it costs

S3 charges per request. Two writes every 10 s is about 520,000 PUTs a month if left on around the clock, so turn it off between shifts or raise `turn.aws.every-sec`. Check current S3 pricing for your region.
