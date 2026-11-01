import logging, time
from datetime import datetime, timezone
from typing import Literal, Optional
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from . import db, providers, security

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("caiporah")
app = FastAPI(title="CAIPORAH CLOUD GAMER", version="0.1.0")
now = lambda: datetime.now(timezone.utc).isoformat(timespec="seconds")
FAILS: dict = {}

class LoginIn(BaseModel):
    username: str = Field(min_length=1, max_length=60)
    password: str = Field(min_length=1, max_length=200)

class DeviceIn(BaseModel):
    name: str = Field(min_length=1, max_length=60)
    provider: Literal["demo", "adb"]
    address: Optional[str] = Field(default=None, max_length=100, pattern=r"^[A-Za-z0-9.\-:]+$")

class SessionIn(BaseModel):
    device_id: int
    package: str = Field(min_length=3, max_length=120, pattern=r"^[A-Za-z0-9_.]+$")

def get_db():
    c = db.conn()
    try:
        yield c
    finally:
        c.close()

def auth(authorization: Optional[str] = Header(None), c=Depends(get_db)):
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "não autenticado")
    row = c.execute("SELECT * FROM tokens WHERE hash=?", (security.token_hash(authorization[7:]),)).fetchone()
    if not row or row["expires"] < time.time():
        raise HTTPException(401, "sessão expirada, entre novamente")
    return row["user_id"]

def event(c, level, msg):
    c.execute("INSERT INTO events(at,level,message) VALUES(?,?,?)", (now(), level, msg))
    c.commit()
    log.log(logging.WARNING if level == "warn" else logging.INFO, msg)

def dev_out(r):
    d = dict(r)
    d["demo"] = d["provider"] == "demo"
    return d

def dev_or_404(c, i):
    r = c.execute("SELECT * FROM devices WHERE id=?", (i,)).fetchone()
    if not r:
        raise HTTPException(404, "dispositivo não encontrado")
    return r

SESS_SQL = """SELECT s.*, COALESCE(d.name,'(removido)') device_name, COALESCE(d.provider='demo',0) demo
  FROM sessions s LEFT JOIN devices d ON d.id=s.device_id """

@app.exception_handler(Exception)
async def boom(request: Request, exc: Exception):
    log.exception("erro interno")
    return JSONResponse({"detail": "erro interno no servidor"}, status_code=500)

@app.get("/api/health")
def health():
    return {"ok": True}

@app.post("/api/auth/login")
def login(b: LoginIn, c=Depends(get_db)):
    f = FAILS.get(b.username, (0, 0))
    if f[0] >= 5 and time.time() - f[1] < 60:
        raise HTTPException(429, "muitas tentativas, aguarde 1 minuto")
    u = c.execute("SELECT * FROM users WHERE username=?", (b.username,)).fetchone()
    if not u or not security.verify_password(b.password, u["pw"]):
        FAILS[b.username] = (f[0] + 1, time.time())
        event(c, "warn", f"login falhou para '{b.username}'")
        raise HTTPException(401, "usuário ou senha inválidos")
    FAILS.pop(b.username, None)
    t = security.new_token()
    c.execute("INSERT INTO tokens VALUES(?,?,?)", (security.token_hash(t), u["id"], time.time() + 86400))
    c.commit()
    return {"token": t}

@app.post("/api/auth/logout")
def logout(authorization: str = Header(...), c=Depends(get_db), _=Depends(auth)):
    c.execute("DELETE FROM tokens WHERE hash=?", (security.token_hash(authorization[7:]),))
    c.commit()
    return {"ok": True}

@app.get("/api/devices")
def list_devices(c=Depends(get_db), _=Depends(auth)):
    return [dev_out(r) for r in c.execute("SELECT * FROM devices ORDER BY id")]

@app.post("/api/devices", status_code=201)
def add_device(b: DeviceIn, c=Depends(get_db), _=Depends(auth)):
    if b.provider == "adb" and not b.address:
        raise HTTPException(422, "informe o endereço do aparelho (ex.: 100.64.0.5:5555)")
    cur = c.execute("INSERT INTO devices(name,provider,address,created_at) VALUES(?,?,?,?)",
                    (b.name, b.provider, b.address, now()))
    c.commit()
    event(c, "info", f"dispositivo '{b.name}' ({b.provider}) cadastrado")
    return dev_out(dev_or_404(c, cur.lastrowid))

@app.delete("/api/devices/{i}")
def del_device(i: int, c=Depends(get_db), _=Depends(auth)):
    dev_or_404(c, i)
    if c.execute("SELECT 1 FROM sessions WHERE device_id=? AND status='active'", (i,)).fetchone():
        raise HTTPException(409, "encerre a sessão ativa antes de remover")
    c.execute("DELETE FROM devices WHERE id=?", (i,))
    c.commit()
    return {"ok": True}

def refresh(c, d):
    res = providers.check(d)
    t = now()
    if res["online"]:
        c.execute("UPDATE devices SET state='online',reason=?,last_seen=?,last_check=? WHERE id=?", (res["reason"], t, t, d["id"]))
    else:
        c.execute("UPDATE devices SET state='offline',reason=?,last_check=? WHERE id=?", (res["reason"], t, d["id"]))
    s = c.execute("SELECT * FROM sessions WHERE device_id=? AND status='active'", (d["id"],)).fetchone()
    if s and not res["online"]:
        c.execute("UPDATE sessions SET status='interrupted',ended_at=?,end_reason=?,last_check=? WHERE id=?",
                  (t, "dispositivo offline: " + res["reason"], t, s["id"]))
        event(c, "warn", f"sessão {s['id']} interrompida: dispositivo '{d['name']}' offline")
    elif s:
        gr = providers.game_running(d, s["package"])
        c.execute("UPDATE sessions SET game_running=?,last_check=? WHERE id=?", (None if gr is None else int(gr), t, s["id"]))
        if gr is False:
            event(c, "warn", f"sessão {s['id']}: o jogo {s['package']} não está aberto")
    c.commit()
    return dev_out(dev_or_404(c, d["id"]))

@app.post("/api/devices/{i}/refresh")
def refresh_device(i: int, c=Depends(get_db), _=Depends(auth)):
    return refresh(c, dev_or_404(c, i))

@app.get("/api/sessions")
def list_sessions(c=Depends(get_db), _=Depends(auth)):
    return [dict(r) for r in c.execute(SESS_SQL + "ORDER BY s.id DESC LIMIT 100")]

@app.post("/api/sessions", status_code=201)
def start_session(b: SessionIn, c=Depends(get_db), _=Depends(auth)):
    d = dev_or_404(c, b.device_id)
    if c.execute("SELECT 1 FROM sessions WHERE device_id=? AND status='active'", (d["id"],)).fetchone():
        raise HTTPException(409, "já existe uma sessão ativa neste dispositivo")
    d = refresh(c, d)
    if d["state"] != "online":
        raise HTTPException(409, "dispositivo offline: " + d["reason"])
    t = now()
    cur = c.execute("INSERT INTO sessions(device_id,package,status,started_at,last_check) VALUES(?,?,?,?,?)",
                    (d["id"], b.package, "active", t, t))
    c.commit()
    event(c, "info", f"sessão {cur.lastrowid} iniciada em '{d['name']}'" + (" [DEMO]" if d["demo"] else ""))
    return dict(c.execute(SESS_SQL + "WHERE s.id=?", (cur.lastrowid,)).fetchone())

@app.post("/api/sessions/{i}/stop")
def stop_session(i: int, c=Depends(get_db), _=Depends(auth)):
    s = c.execute("SELECT * FROM sessions WHERE id=?", (i,)).fetchone()
    if not s:
        raise HTTPException(404, "sessão não encontrada")
    if s["status"] != "active":
        raise HTTPException(409, "a sessão já está encerrada")
    c.execute("UPDATE sessions SET status='stopped',ended_at=?,end_reason='encerrada pelo usuário' WHERE id=?", (now(), i))
    c.commit()
    event(c, "info", f"sessão {i} encerrada")
    return dict(c.execute(SESS_SQL + "WHERE s.id=?", (i,)).fetchone())

@app.get("/api/events")
def events(c=Depends(get_db), _=Depends(auth)):
    return [dict(r) for r in c.execute("SELECT * FROM events ORDER BY id DESC LIMIT 50")]

@app.get("/api/providers")
def catalog(_=Depends(auth)):
    return providers.CATALOG

app.mount("/", StaticFiles(directory="frontend", html=True), name="site")
