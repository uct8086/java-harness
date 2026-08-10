import mysql.connector

conn = mysql.connector.connect(
    host='127.0.0.1',
    port=3306,
    user='root',
    password='root.2026',
    database='uct8086_ai',
    connection_timeout=5
)
print("CONNECTED to uct8086_ai!")
cursor = conn.cursor()

# Show tables first
cursor.execute("SHOW TABLES")
tables = cursor.fetchall()
print("Tables:", [t[0] for t in tables])

# Query t_report
try:
    cursor.execute("SELECT COUNT(*) FROM t_report")
    count = cursor.fetchone()[0]
    print(f"t_report 记录数: {count}")
    
    # Also show some sample data
    cursor.execute("SELECT * FROM t_report LIMIT 3")
    rows = cursor.fetchall()
    for row in rows:
        print("  Row:", row)
        
except Exception as e:
    print(f"Query error: {e}")

# Show table structure
try:
    cursor.execute("DESCRIBE t_report")
    print("\nt_report structure:")
    for col in cursor.fetchall():
        print(f"  {col[0]}: {col[1]}")
except Exception as e:
    print(f"DESC error: {e}")

conn.close()
