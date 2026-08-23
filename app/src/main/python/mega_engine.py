"""
PixStreamo MEGA Core Engine
---------------------------
Optimized with Metadata Caching to prevent repeated API delays.
"""
import json
import sys
import base64
import requests
import os
import random
import time
from Crypto.Cipher import AES
from Crypto.Util import Counter

# --- Chaquopy Environment Patch ---
class FakeAsyncio:
    def __init__(self):
        self.__name__ = "asyncio"
        self.iscoroutinefunction = lambda obj: False
        self.iscoroutine = lambda obj: False
    def coroutine(self, f): return f
    def sleep(self, delay): import time; time.sleep(delay)
    def get_event_loop(self): return self
    def __getattr__(self, name): return lambda *args, **kwargs: None
sys.modules["asyncio"] = FakeAsyncio()

# --- MEGA Decryption Utilities ---
def base64_url_decode(data):
    try:
        data = data.replace('-', '+').replace('_', '/')
        chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        data = "".join(c for c in data if c in chars)
        data += '=' * ((4 - len(data) % 4) % 4)
        return base64.b64decode(data)
    except: return b''

def aes_ecb_decrypt(data, key):
    return AES.new(key, AES.MODE_ECB).decrypt(data)

def decrypt_attr(attr_data, key):
    try:
        attr_data = base64_url_decode(attr_data)
        cipher = AES.new(key, AES.MODE_CBC, b'\x00' * 16)
        decrypted = cipher.decrypt(attr_data)
        if decrypted.startswith(b'MEGA'):
            res = decrypted[4:].split(b'\0', 1)[0].decode('utf-8', errors='ignore')
            return json.loads(res)
    except: pass
    return {}

class MegaManager:
    def __init__(self):
        self.session = requests.Session()
        self.cache_dir = None

    def parse_url(self, url):
        url = url.strip()
        if not url: return None, None, False
        if "/folder/" in url:
            p = url.split("/folder/")[1].split("#")
            return p[0], p[1] if len(p) > 1 else "", True
        elif "#F!" in url:
            p = url.split("#F!")[1].split("!")
            return p[0], p[1] if len(p) > 1 else "", True
        if "/file/" in url:
            p = url.split("/file/")[1].split("#")
            return p[0], p[1] if len(p) > 1 else "", False
        elif "#!" in url and not "#F!" in url:
            p = url.split("#!")[1].split("!")
            return p[0], p[1] if len(p) > 1 else "", False
        return None, None, False

    def fetch_config(self, url, dest_path):
        self.cache_dir = dest_path
        h, k, is_folder = self.parse_url(url)
        if not h or not k:
            r = self.session.get(url, timeout=15)
            r.raise_for_status()
            return r.text.strip()
        if is_folder:
            return json.dumps({"Folders": [{"name": "Shared Gallery", "url": url.strip()}]})

        api_url = f"https://g.api.mega.co.nz/cs?id={random.randint(0, 1000)}"
        payloads = [[{"a": "g", "g": 1, "p": h}], [{"a": "g", "g": 1, "n": h}]]
        resp = None
        for p in payloads:
            try:
                r = self.session.post(api_url, json=p, timeout=15).json()
                if isinstance(r, list): r = r[0]
                if isinstance(r, dict) and 'g' in r:
                    resp = r; break
            except: continue
        if not resp: raise ValueError("MEGA access denied.")

        node_key = base64_url_decode(k)
        key_bytes = bytes([node_key[i] ^ node_key[i+16] for i in range(16)])
        iv = node_key[16:24] + b'\x00' * 8; iv_val = 0
        for b in iv: iv_val = (iv_val << 8) | b
        decryptor = AES.new(key_bytes, AES.MODE_CTR, counter=Counter.new(128, initial_value=iv_val))
        data = self.session.get(resp['g'], timeout=15).content
        decrypted = decryptor.decrypt(data)
        if decrypted.startswith(b'\xef\xbb\xbf'): decrypted = decrypted[3:] 
        return decrypted.decode('utf-8', errors='ignore').strip()

    def get_folder_nodes(self, url):
        h, k, is_f = self.parse_url(url)
        if not h or not is_f: return "[]"
        
        # Metadata Caching
        cache_file = None
        if self.cache_dir:
            cache_file = os.path.join(self.cache_dir, f"meta_{h}.json")
            if os.path.exists(cache_file):
                mtime = os.path.getmtime(cache_file)
                if (time.time() - mtime < 3600):
                    f_ptr = open(cache_file, 'r')
                    data = f_ptr.read()
                    f_ptr.close()
                    return data

        try:
            f_k = base64_url_decode(k)
            api_url = f"https://g.api.mega.co.nz/cs?id={random.randint(0, 1000)}&n={h}"
            r = self.session.post(api_url, json=[{"a": "f", "c": 1, "r": 1}], timeout=15).json()
            resp = r[0] if isinstance(r, list) else r
            if not resp or "f" not in resp: return "[]"
            
            node_list = []; img_exts = ('.png', '.jpg', '.jpeg', '.gif', '.bmp', '.webp')
            for node in resp["f"]:
                if node.get("t") == 0:
                    try:
                        nk_str = node.get("k")
                        if ':' in nk_str: nk_str = nk_str.split(':', 1)[1]
                        enc_k = base64_url_decode(nk_str); dec_k = b''
                        for i in range(0, len(enc_k), 16): dec_k += aes_ecb_decrypt(enc_k[i:i+16], f_k)
                        attr_key = bytes([dec_k[i] ^ dec_k[i+16] for i in range(16)]) if len(dec_k) == 32 else dec_k[:16]
                        attrs = decrypt_attr(node.get("a"), attr_key); name = attrs.get("n", "Unknown")
                        if any(name.lower().endswith(ext) for ext in img_exts):
                            b64_k = base64.urlsafe_b64encode(dec_k).decode('utf-8').rstrip('=')
                            node_list.append({"name": name, "handle": node['h'], "key": b64_k})
                    except: continue
            
            result_json = json.dumps(node_list)
            if cache_file:
                f_ptr = open(cache_file, 'w')
                f_ptr.write(result_json)
                f_ptr.close()
            return result_json
        except: return "[]"

    def decrypt_image_bytes(self, handle, k_b64, f_url):
        try:
            f_h, _, _ = self.parse_url(f_url)
            if not f_h: return b''
            node_key = base64_url_decode(k_b64)
            api_url = f"https://g.api.mega.co.nz/cs?id={random.randint(0, 1000)}&n={f_h}"
            r = self.session.post(api_url, json=[{"a": "g", "g": 1, "n": handle}], timeout=15).json()
            resp = r[0] if isinstance(r, list) else r
            if 'g' not in resp: return b''
            k_b = bytes([node_key[i] ^ node_key[i+16] for i in range(16)])
            iv = node_key[16:24] + b'\x00' * 8; iv_val = 0
            for b in iv: iv_val = (iv_val << 8) | b
            decryptor = AES.new(k_b, AES.MODE_CTR, counter=Counter.new(128, initial_value=iv_val))
            return decryptor.decrypt(self.session.get(resp['g'], timeout=30).content)
        except: return b''

manager = MegaManager()
def fetch_config(u, p): return manager.fetch_config(u, p)
def get_folder_nodes(u): return manager.get_folder_nodes(u)
def decrypt_image_bytes(h, k, f): return manager.decrypt_image_bytes(h, k, f)
