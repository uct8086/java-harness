import mysql.connector
import sys

try:
    conn = mysql.connector.connect(
        host='127.0.0.1',
        port=3306,
        user='root',
        password='root.2026',
        connection_timeout=5
    )
    print("CONNECTED!")
    cursor = conn.cursor()
    cursor.execute("SHOW DATABASES")
    for db in cursor.fetchall():
        print("DB:", db[0])
    
    # Try find OAH database
    cursor.execute("SHOW DATABASES LIKE '%oah%'")
    oah_dbs = cursor.fetchall()
    print("OAH-like DBs:", oah_dbs)
    
    conn.close()
except Exception as e:
    print(f"ERROR: {e}")
    sys.exit(1)
