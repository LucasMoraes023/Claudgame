"""Provedores de Android. Só 'demo' (simulado) e 'adb' (aparelho real) existem de fato."""
import shutil, subprocess

def _adb(*args, timeout=8):
    if not shutil.which("adb"):
        return None, "adb não encontrado no servidor"
    try:
        return subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout), None
    except subprocess.TimeoutExpired:
        return None, "tempo esgotado ao falar com o aparelho"

def check(device) -> dict:
    if device["provider"] == "demo":
        return {"online": True, "reason": "DEMO: dispositivo simulado, não é um Android real"}
    addr = device["address"]
    if not addr:
        return {"online": False, "reason": "sem endereço configurado"}
    if ":" in addr:
        _adb("connect", addr)
    r, err = _adb("-s", addr, "get-state")
    if err:
        return {"online": False, "reason": err}
    if r.returncode == 0 and r.stdout.strip() == "device":
        return {"online": True, "reason": "conectado via ADB"}
    return {"online": False, "reason": (r.stderr or r.stdout).strip() or "aparelho não respondeu"}

def game_running(device, package):
    """True/False, ou None se não foi possível verificar."""
    if device["provider"] == "demo":
        return True
    r, err = _adb("-s", device["address"], "shell", "pidof", package)
    if err or r.returncode > 1:
        return None
    return bool(r.stdout.strip())

CATALOG = [
    {"id": "demo", "nome": "Modo DEMO", "status": "implementado", "limite_gratis": "sem limite",
     "duracao": "sem limite", "suspensao": "não", "cartao": "não", "android": "nenhum (simulado)",
     "jogos": "nenhum", "afk": "NÃO. Serve só para testar o painel."},
    {"id": "adb", "nome": "Aparelho Android próprio via ADB", "status": "implementado",
     "limite_gratis": "custo zero se você já tem o aparelho", "duracao": "enquanto o aparelho estiver ligado e na rede",
     "suspensao": "depende do aparelho (bateria, economia de energia)", "cartao": "não",
     "android": "real, com depuração ADB ativada", "jogos": "os que rodam no próprio aparelho",
     "afk": "possível, respeitando as regras do jogo. O servidor da API precisa alcançar o aparelho por rede segura (ex.: VPN privada)."},
    {"id": "nuvem", "nome": "Android virtualizado / emulador em nuvem", "status": "não implementado",
     "limite_gratis": "não documentado aqui: confira o site do provedor", "duracao": "confira o provedor",
     "suspensao": "confira o provedor", "cartao": "confira o provedor",
     "android": "exige virtualização; normalmente sem GPU em planos grátis", "jogos": "não garantido",
     "afk": "não verificado. Nenhuma integração foi inventada."},
    {"id": "ci", "nome": "GitHub Actions / Codespaces / Colab", "status": "não recomendado",
     "limite_gratis": "cotas limitadas", "duracao": "sessões temporárias", "suspensao": "sim, automática",
     "cartao": "varia", "android": "não é servidor Android", "jogos": "não", "afk": "NÃO. Não são servidores permanentes."},
]
