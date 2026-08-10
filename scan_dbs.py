import mysql.connector

conn = mysql.connector.connect(
    host='127.0.0.1',
    port=3306,
    user='root',
    password='root.2026',
    connection_timeout=5
)
cursor = conn.cursor()

# List all databases
cursor.execute("SHOW DATABASES")
print("All databases:")
for db in cursor.fetchall():
    dbname = db[0]
    if dbname not in ('information_schema', 'mysql', 'performance_schema', 'sys'):
        print(f"  {dbname}")
        # Check tables in this db
        cursor.execute(f"USE `{dbname}`")
        cursor.execute("SHOW TABLES")
        tables = cursor.fetchall()
        for t in tables:
            print(f"    Table: {t[0]}")
            # Check if t_report
            if 'report' in t[0].lower():
                cursor.execute(f"SELECT COUNT(*) FROM `{t[0]}`")
                print(f"      COUNT: {cursor.fetchone()[0]}")

conn.close()
