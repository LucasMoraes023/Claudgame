import os, tempfile
os.environ["CAIPORAH_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
os.environ["ADMIN_USER"] = "admin"
os.environ["ADMIN_PASSWORD"] = "senha-de-teste"
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app import providers

c = TestClient(app, raise_server_exceptions=False)

def login(pw="senha-de-teste"):
    return c.post("/api/auth/login", json={"username": "admin", "password": pw})

@pytest.fixture(scope="module")
def H():
    return {"Authorization": "Bearer " + login().json()["token"]}

def test_login_ok_e_falha():
    assert login().status_code == 200
    assert login("errada").status_code == 401

def test_exige_autenticacao():
    assert c.get("/api/devices").status_code == 401

def test_validacao():
    h = {"Authorization": "Bearer " + login().json()["token"]}
    assert c.post("/api/devices", json={"name": "", "provider": "demo"}, headers=h).status_code == 422
    assert c.post("/api/devices", json={"name": "x", "provider": "adb"}, headers=h).status_code == 422
    assert c.post("/api/devices", json={"name": "x", "provider": "adb", "address": "a; rm -rf"}, headers=h).status_code == 422

def test_dispositivo_inexistente(H):
    assert c.post("/api/devices/999/refresh", headers=H).status_code == 404
    assert c.post("/api/sessions", json={"device_id": 999, "package": "com.a.b"}, headers=H).status_code == 404

def test_demo_online_e_sessao(H):
    d = c.post("/api/devices", json={"name": "Demo", "provider": "demo"}, headers=H).json()
    assert d["state"] == "unknown" and d["demo"] is True
    s = c.post("/api/sessions", json={"device_id": d["id"], "package": "com.jogo.teste"}, headers=H)
    assert s.status_code == 201 and s.json()["demo"] == 1
    assert c.post("/api/sessions", json={"device_id": d["id"], "package": "com.jogo.teste"}, headers=H).status_code == 409
    sid = s.json()["id"]
    assert c.post(f"/api/sessions/{sid}/stop", headers=H).json()["status"] == "stopped"
    assert c.post(f"/api/sessions/{sid}/stop", headers=H).status_code == 409

def test_adb_sem_conexao_fica_offline(H, monkeypatch):
    monkeypatch.setattr(providers, "_adb", lambda *a, **k: (None, "adb não encontrado no servidor"))
    d = c.post("/api/devices", json={"name": "Real", "provider": "adb", "address": "10.0.0.9:5555"}, headers=H).json()
    r = c.post(f"/api/devices/{d['id']}/refresh", headers=H).json()
    assert r["state"] == "offline"
    assert c.post("/api/sessions", json={"device_id": d["id"], "package": "com.a.b"}, headers=H).status_code == 409

def test_queda_de_conexao_interrompe_sessao(H, monkeypatch):
    d = c.post("/api/devices", json={"name": "Cai", "provider": "demo"}, headers=H).json()
    sid = c.post("/api/sessions", json={"device_id": d["id"], "package": "com.a.b"}, headers=H).json()["id"]
    monkeypatch.setattr(providers, "check", lambda dev: {"online": False, "reason": "falha de conexão"})
    c.post(f"/api/devices/{d['id']}/refresh", headers=H)
    s = [x for x in c.get("/api/sessions", headers=H).json() if x["id"] == sid][0]
    assert s["status"] == "interrupted" and "offline" in s["end_reason"]

def test_erro_interno_nao_vaza(H, monkeypatch):
    def quebra(*a, **k): raise RuntimeError("segredo interno")
    monkeypatch.setattr(providers, "check", quebra)
    d = c.get("/api/devices", headers=H).json()[0]
    r = c.post(f"/api/devices/{d['id']}/refresh", headers=H)
    assert r.status_code == 500 and "segredo" not in r.text
