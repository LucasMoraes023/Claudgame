import hashlib, hmac, os, secrets

def hash_password(pw: str) -> str:
    salt = os.urandom(16)
    h = hashlib.pbkdf2_hmac("sha256", pw.encode(), salt, 200_000)
    return salt.hex() + "$" + h.hex()

def verify_password(pw: str, stored: str) -> bool:
    try:
        salt, h = stored.split("$")
        calc = hashlib.pbkdf2_hmac("sha256", pw.encode(), bytes.fromhex(salt), 200_000)
        return hmac.compare_digest(calc.hex(), h)
    except ValueError:
        return False

def new_token() -> str:
    return secrets.token_urlsafe(32)

def token_hash(t: str) -> str:
    return hashlib.sha256(t.encode()).hexdigest()
