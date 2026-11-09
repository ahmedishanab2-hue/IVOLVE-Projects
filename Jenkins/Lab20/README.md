# Lab 20: Role-based Authorization

## Objective

- Create two users in Jenkins: `user1` and `user2`.
- Give `user1` the admin role and `user2` a read-only role using role-based authorization.

## What the lab asked for

| Item | Value |
|------|-------|
| User 1 | `user1` → admin role |
| User 2 | `user2` → read-only role |

## My setup

- Jenkins running locally (Docker or service)
- Logged in as the admin user with access to **Manage Jenkins**

## Step 1: Enable role-based authorization

Jenkins does not ship with roles by default, so I installed the **Role Strategy** plugin:

```text
Manage Jenkins → Plugins → Available plugins → Role Strategy → Install
```

After installation Jenkins restarts (or I used **Reload Configuration**).

## Step 2: Create the users

```text
Manage Jenkins → Manage Users → New User
```

I created both users:

| Username | Password (example) |
|----------|--------------------|
| `user1`  | `user1Pass`        |
| `user2`  | `user2Pass`        |

Both now appear in the user list.

## Step 3: Define the roles

```text
Manage Jenkins → Manage and Assign Roles → Manage Roles
```

In the **Global roles** table I added two roles:

| Role | Permissions |
|------|-------------|
| `admin`    | Overall → Administer (everything) |
| `readonly` | Overall → Read only |

Then clicked **Save**.

## Step 4: Assign the roles

```text
Manage Jenkins → Manage and Assign Roles → Assign Roles
```

In the **Global roles** matrix I checked:

| User | admin | readonly |
|------|-------|----------|
| `user1` | ✅ | — |
| `user2` | — | ✅ |

Then clicked **Save**.

## Step 5: Verify

Logged out and tested both accounts:

- **`user1`** → sees **Manage Jenkins**, can create jobs and change configuration → **admin works** ✅
- **`user2`** → can open the dashboard and view jobs, but **Manage Jenkins** is hidden and job configuration is denied → **read-only works** ✅

Quick check with the CLI as well:

```bash
# admin user can read secrets/config
curl -s -u user1:user1Pass http://localhost:8080/api/json?pretty=true | head

# read-only user gets only basic info, no manage endpoints
curl -s -o /dev/null -w "%{http_code}\n" -u user2:user2Pass \
  http://localhost:8080/manage
# 403 Forbidden
```

## Result

| Requirement | Result |
|-------------|--------|
| Create `user1` and `user2` | ✅ Both created |
| `user1` = admin role | ✅ Full admin access |
| `user2` = read-only role | ✅ Dashboard/view only, management denied |
