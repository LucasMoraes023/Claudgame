import logging, os, secrets, sqlite3
from . import security
log = logging.getLogger("caiporah")
SCHEMA = """
CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY, username TEXT UNIQUE, pw TEXT);
CREATE TABLE IF NOT EXISTS tokens(hash TEXT PRIMARY KEY, user_id INT, expires REAL);
CREATE TABLE IF NOT EXISTS devices(id INTEGER PRIMARY KEY, name TEXT, provider TEXT, address TEXT,
  state TEXT DEFAULT 'unknown', reason TEXT DEFAULT 'ainda não verificado', last_seen TEXT, last_check TEXT, created_at TEXT);
CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY, device_id INT, package TEXT, status TEXT,
  game_running INT, started_at TEXT, ended_at TEXT, end_reason TEXT, last_check TEXT);
CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY, at TEXT, level TEXT, message TEXT);
"""

def conn():
    c = sqlite3.connect(os.environ.get("CAIPORAH_DB", "caiporah.db"))
    c.row_factory = sqlite3.Row
    c.executescript(SCHEMA)
    if not c.execute("SELECT 1 FROM users").fetchone():
        u = os.environ.get("ADMIN_USER", "admin")
        p = os.environ.get("ADMIN_PASSWORD")
        if not p:
            p = secrets.token_urlsafe(9)
            log.warning("Senha inicial gerada para '%s': %s (defina ADMIN_PASSWORD)", u, p)
        c.execute("INSERT INTO users(username,pw) VALUES(?,?)", (u, security.hash_password(p)))
        c.commit()
    return c
