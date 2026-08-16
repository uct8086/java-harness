"""One-off migration: add `internal` column and hide existing sub-agent sessions.

1. ALTER TABLE harness_session ADD COLUMN internal TINYINT(1) NOT NULL DEFAULT 0
   (idempotent: ignores duplicate-column error)
2. Show recent sessions so sub-agent sessions can be identified.
3. Mark sessions by id (dry-run by default; pass --apply to write).
4. Clear Redis session-list ZSET cache so the list rebuilds from DB.
"""
import sys

import mysql.connector

MYSQL = dict(host='127.0.0.1', port=3306, user='root', password='root.2026', database='uct8086_ai')
REDIS_HOST, REDIS_PORT = '127.0.0.1', 6379


def main():
    apply = '--apply' in sys.argv
    conn = mysql.connector.connect(**MYSQL)
    cursor = conn.cursor()

    # 1. add column (idempotent)
    try:
        cursor.execute(
            "ALTER TABLE harness_session ADD COLUMN internal TINYINT(1) NOT NULL DEFAULT 0 "
            "COMMENT 'internal sub-agent session, hidden from list'")
        conn.commit()
        print('[OK] column `internal` added')
    except mysql.connector.Error as e:
        if e.errno == 1060:  # duplicate column
            print('[SKIP] column `internal` already exists')
        else:
            raise

    # 2. show recent sessions
    cursor.execute(
        "SELECT id, name, message_count, internal, created_at FROM harness_session "
        "ORDER BY created_at DESC LIMIT 20")
    rows = cursor.fetchall()
    print('\nrecent sessions (id | name | msg_count | internal | created_at):')
    for r in rows:
        print(' ', r[0], '|', r[1], '|', r[2], '|', r[3], '|', r[4])

    # 3. mark sub-agent sessions: created by old AgentTool code, name LIKE 'session-%'
    #    with few messages, exclude any explicitly named sessions. Dry-run first.
    cursor.execute(
        "SELECT id, name, message_count FROM harness_session "
        "WHERE internal = 0 AND name LIKE 'session-%' AND message_count <= 4")
    candidates = cursor.fetchall()
    print('\ncandidate sub-agent sessions to hide:')
    for r in candidates:
        print(' ', r[0], '|', r[1], '|', r[2])

    if apply and candidates:
        ids = [r[0] for r in candidates]
        fmt = ','.join(['%s'] * len(ids))
        cursor.execute(f"UPDATE harness_session SET internal = 1 WHERE id IN ({fmt})", ids)
        conn.commit()
        print(f'[OK] marked {cursor.rowcount} sessions internal=1')

    conn.close()

    # 4. clear redis session-list cache (best effort)
    if apply:
        try:
            import redis
            r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, decode_responses=True)
            keys = list(r.scan_iter('harness:session:zset:*'))
            if keys:
                r.delete(*keys)
            print(f'[OK] cleared {len(keys)} redis zset keys')
        except Exception as e:
            print(f'[WARN] redis cleanup skipped: {e}')

    print('\ndone' + (' (applied)' if apply else ' (dry-run, re-run with --apply)'))


if __name__ == '__main__':
    main()
