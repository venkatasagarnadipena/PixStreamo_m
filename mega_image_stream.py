import asyncio
# Python 3.13 compatibility fix for legacy asyncio calls
if not hasattr(asyncio, "coroutine"):
    asyncio.coroutine = lambda f: f

import os
import json
import base64
import struct
import threading
from concurrent.futures import ThreadPoolExecutor
import requests
import tkinter as tk
from tkinter import ttk, messagebox
from PIL import Image, ImageTk

# --- AES Decryption Engine ---
try:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    from cryptography.hazmat.backends import default_backend

    def aes_ecb_decrypt(data, key):
        c = Cipher(algorithms.AES(key), modes.ECB(), backend=default_backend())
        return c.decryptor().update(data) + c.decryptor().finalize()

    def aes_cbc_decrypt(data, key):
        c = Cipher(algorithms.AES(key), modes.CBC(b'\0' * 16), backend=default_backend())
        return c.decryptor().update(data) + c.decryptor().finalize()

    def create_ctr_decryptor(key_bytes, iv_bytes):
        c = Cipher(algorithms.AES(key_bytes), modes.CTR(iv_bytes), backend=default_backend())
        return c.decryptor()

except ImportError:
    from Crypto.Cipher import AES
    from Crypto.Util import Counter

    def aes_ecb_decrypt(data, key):
        return AES.new(key, AES.MODE_ECB).decrypt(data)

    def aes_cbc_decrypt(data, key):
        return AES.new(key, AES.MODE_CBC, b'\0' * 16).decrypt(data)

    def create_ctr_decryptor(key_bytes, iv_bytes):
        ctr = Counter.new(128, initial_value=int.from_bytes(iv_bytes, byteorder='big'))
        cipher = AES.new(key_bytes, AES.MODE_CTR, counter=ctr)
        class DecryptorWrapper:
            def update(self, chunk):
                return cipher.decrypt(chunk)
            def finalize(self):
                return b""
        return DecryptorWrapper()


# --- MEGA Decryption Helpers ---
def base64_url_decode(data):
    data += '=' * ((4 - len(data) % 4) % 4)
    return base64.b64decode(data.replace('-', '+').replace('_', '/'))

def bytes_to_a32(b):
    if len(b) % 4 != 0:
        b += b'\0' * (4 - len(b) % 4)
    return list(struct.unpack('>' + 'I' * (len(b) // 4), b))

def parse_mega_folder_url(url):
    url = url.strip()
    folder_id, folder_key = None, None

    if "/folder/" in url and "#" in url:
        part = url.split("/folder/")[1]
        if "#" in part:
            folder_id, folder_key = part.split("#", 1)
    elif "#F!" in url:
        part = url.split("#F!")[1]
        if "!" in part:
            folder_id, folder_key = part.split("!", 1)

    return folder_id, folder_key

def decrypt_node_key(enc_key_str, folder_key_bytes):
    if ':' in enc_key_str:
        enc_key_str = enc_key_str.split(':', 1)[1]

    enc_key_bytes = base64_url_decode(enc_key_str)
    dec_bytes = b''
    for i in range(0, len(enc_key_bytes), 16):
        chunk = enc_key_bytes[i:i+16]
        dec_bytes += aes_ecb_decrypt(chunk, folder_key_bytes)

    return bytes_to_a32(dec_bytes)

def decrypt_node_attributes(enc_attr_str, dec_a32_key):
    attr_key_a32 = [
        dec_a32_key[0] ^ dec_a32_key[4],
        dec_a32_key[1] ^ dec_a32_key[5],
        dec_a32_key[2] ^ dec_a32_key[6],
        dec_a32_key[3] ^ dec_a32_key[7]
    ]
    attr_key_bytes = struct.pack('>4I', *attr_key_a32)

    enc_attr_bytes = base64_url_decode(enc_attr_str)
    dec_attr_bytes = aes_cbc_decrypt(enc_attr_bytes, attr_key_bytes)

    if dec_attr_bytes.startswith(b'MEGA'):
        raw_json = dec_attr_bytes[4:].rstrip(b'\0')
        return json.loads(raw_json.decode('utf-8', errors='ignore'))
    else:
        raise ValueError("Invalid attribute decryption payload.")

def download_and_decrypt_stream(folder_id, node, dec_a32, dest_path):
    """Direct chunked HTTP stream downloader with on-the-fly AES-CTR decryption."""
    file_handle = node['h']
    dl_api_url = f"https://g.api.mega.co.nz/cs?id=100001&n={folder_id}"
    payload = [{"a": "g", "g": 1, "n": file_handle}]
    
    resp = requests.post(dl_api_url, json=payload, timeout=15)
    res_data = resp.json()

    if isinstance(res_data, list) and len(res_data) > 0:
        res_data = res_data[0]

    if not isinstance(res_data, dict) or 'g' not in res_data:
        raise ValueError("Could not obtain file stream URL from MEGA.")

    file_url = res_data['g']

    key_a32 = [
        dec_a32[0] ^ dec_a32[4],
        dec_a32[1] ^ dec_a32[5],
        dec_a32[2] ^ dec_a32[6],
        dec_a32[3] ^ dec_a32[7]
    ]
    iv_a32 = [dec_a32[4], dec_a32[5], 0, 0]

    key_bytes = struct.pack('>4I', *key_a32)
    iv_bytes = struct.pack('>4I', *iv_a32)

    decryptor = create_ctr_decryptor(key_bytes, iv_bytes)

    f_resp = requests.get(file_url, stream=True, timeout=30)
    f_resp.raise_for_status()

    temp_path = dest_path + ".tmp"
    with open(temp_path, 'wb') as f:
        for chunk in f_resp.iter_content(chunk_size=65536):
            if chunk:
                f.write(decryptor.update(chunk))
        final_bytes = decryptor.finalize()
        if final_bytes:
            f.write(final_bytes)

    os.replace(temp_path, dest_path)


# --- GUI Application ---
class ReliableMegaGridViewerApp:
    def __init__(self, root, initial_url=""):
        self.root = root
        self.root.title("MEGA Auto-Streaming Grid Viewer")
        self.root.geometry("1100x700")
        self.root.minsize(800, 550)

        # Cache directory
        self.cache_dir = "./mega_cache"
        os.makedirs(self.cache_dir, exist_ok=True)

        self.folder_id = None
        self.image_items = []  # Tuples: (filename, node_dict, dec_a32)
        self.grid_widgets = []
        self.thumbnail_objs = {}
        self.current_index = -1
        self.current_img_obj = None
        
        # Background worker thread pool (3 concurrent downloads)
        self.executor = ThreadPoolExecutor(max_workers=3)
        self.stop_bg_tasks = False

        self._build_ui(initial_url)

    def _build_ui(self, initial_url):
        style = ttk.Style()
        style.theme_use("clam")

        # Top Control Bar
        top_frame = ttk.LabelFrame(self.root, text=" MEGA Shareable Link ", padding=10)
        top_frame.pack(fill="x", padx=10, pady=5)

        self.url_var = tk.StringVar(value=initial_url)
        url_entry = ttk.Entry(top_frame, textvariable=self.url_var, font=("Arial", 10))
        url_entry.pack(side="left", fill="x", expand=True, padx=(0, 10))

        self.btn_fetch = ttk.Button(top_frame, text="Load Image List", command=self.start_fetch_metadata)
        self.btn_fetch.pack(side="right")

        # Status Line
        self.status_var = tk.StringVar(value="Click 'Load Image List' to start auto-loading grid thumbnails.")
        status_label = ttk.Label(self.root, textvariable=self.status_var, font=("Arial", 10, "italic"))
        status_label.pack(anchor="w", padx=15, pady=(0, 5))

        # Main Split Paned View
        paned = ttk.PanedWindow(self.root, orient="horizontal")
        paned.pack(fill="both", expand=True, padx=10, pady=5)

        # LEFT PANEL: Grid
        left_frame = ttk.Frame(paned, padding=5)
        paned.add(left_frame, weight=2)

        grid_title = ttk.Label(left_frame, text="Gallery Grid (Auto-Streaming Previews):", font=("Arial", 10, "bold"))
        grid_title.pack(anchor="w", pady=(0, 5))

        self.grid_canvas = tk.Canvas(left_frame, bg="#f0f0f0", highlightthickness=0)
        grid_scrollbar = ttk.Scrollbar(left_frame, orient="vertical", command=self.grid_canvas.yview)
        
        self.grid_container = ttk.Frame(self.grid_canvas)
        self.grid_container.bind(
            "<Configure>", 
            lambda e: self.grid_canvas.configure(scrollregion=self.grid_canvas.bbox("all"))
        )
        self.canvas_window = self.grid_canvas.create_window((0, 0), window=self.grid_container, anchor="nw")

        self.grid_canvas.configure(yscrollcommand=grid_scrollbar.set)
        grid_scrollbar.pack(side="right", fill="y")
        self.grid_canvas.pack(side="left", fill="both", expand=True)

        self.grid_canvas.bind_all("<MouseWheel>", self._on_mousewheel)

        # RIGHT PANEL: Full Image Preview
        right_frame = ttk.Frame(paned, padding=5)
        paned.add(right_frame, weight=3)

        self.image_container = ttk.Frame(right_frame, relief="sunken", padding=5)
        self.image_container.pack(fill="both", expand=True, pady=(0, 5))

        self.img_label = ttk.Label(self.image_container, text="Click any item to view in full size", anchor="center")
        self.img_label.pack(fill="both", expand=True)

        # Navigation Controls
        nav_frame = ttk.Frame(right_frame, padding=5)
        nav_frame.pack(fill="x")

        self.btn_prev = ttk.Button(nav_frame, text="◀ Previous", command=self.show_prev_image, state="disabled")
        self.btn_prev.pack(side="left", padx=5)

        self.counter_var = tk.StringVar(value="0 / 0")
        counter_label = ttk.Label(nav_frame, textvariable=self.counter_var, font=("Arial", 10, "bold"))
        counter_label.pack(side="left", expand=True)

        self.btn_next = ttk.Button(nav_frame, text="Next ▶", command=self.show_next_image, state="disabled")
        self.btn_next.pack(side="right", padx=5)

        self.image_container.bind("<Configure>", self._on_window_resize)

    def _on_mousewheel(self, event):
        self.grid_canvas.yview_scroll(int(-1 * (event.delta / 120)), "units")

    def start_fetch_metadata(self):
        raw_url = self.url_var.get().strip()
        folder_id, folder_key = parse_mega_folder_url(raw_url)

        if not folder_id or not folder_key:
            messagebox.showwarning("Input Error", "Invalid MEGA folder link format.")
            return

        self.stop_bg_tasks = True
        self.folder_id = folder_id
        self.btn_fetch.config(state="disabled")
        self.status_var.set("Reading folder contents from MEGA...")

        for widget in self.grid_container.winfo_children():
            widget.destroy()
        self.grid_widgets.clear()
        self.thumbnail_objs.clear()
        self.image_items.clear()

        threading.Thread(
            target=self._fetch_metadata_thread, 
            args=(folder_id, folder_key), 
            daemon=True
        ).start()

    def _fetch_metadata_thread(self, folder_id, folder_key):
        valid_extensions = ('.png', '.jpg', '.jpeg', '.gif', '.bmp', '.webp')
        try:
            api_url = f"https://g.api.mega.co.nz/cs?id=100000&n={folder_id}"
            payload = [{"a": "f", "c": 1, "r": 1}]

            resp = requests.post(api_url, json=payload, timeout=15)
            res_data = resp.json()

            if isinstance(res_data, list) and len(res_data) > 0:
                res_data = res_data[0]

            if not isinstance(res_data, dict) or 'f' not in res_data:
                raise ValueError("Unexpected folder structure returned by MEGA API.")

            nodes = res_data['f']
            folder_key_bytes = base64_url_decode(folder_key)

            found_images = []
            for node in nodes:
                if isinstance(node, dict) and node.get('t') == 0:
                    try:
                        dec_a32 = decrypt_node_key(node['k'], folder_key_bytes)
                        attr = decrypt_node_attributes(node['a'], dec_a32)

                        filename = attr.get('n', '')
                        if filename and filename.lower().endswith(valid_extensions):
                            found_images.append((filename, node, dec_a32))
                    except Exception:
                        continue

            found_images.sort(key=lambda x: x[0])
            self.image_items = found_images

            self.root.after(0, self._on_metadata_loaded)

        except Exception as err:
            err_msg = f"Failed to read folder contents:\n{err}"
            self.root.after(0, self._on_error, err_msg)

    def _on_metadata_loaded(self):
        self.btn_fetch.config(state="normal")
        if not self.image_items:
            self.status_var.set("No valid image files found in this folder.")
            messagebox.showinfo("Empty", "No image files found in folder.")
            return

        self.status_var.set(f"Loaded {len(self.image_items)} items. Auto-streaming previews...")
        self._populate_skeleton_grid()

        self.stop_bg_tasks = False
        
        # Submit background download tasks to thread pool
        for idx in range(len(self.image_items)):
            self.executor.submit(self._background_download_worker, idx)

    def _populate_skeleton_grid(self):
        columns = 2
        for idx, (filename, _, _) in enumerate(self.image_items):
            row = idx // columns
            col = idx % columns

            card = tk.Frame(
                self.grid_container, 
                bg="#e8e8e8", 
                highlightbackground="#cccccc", 
                highlightthickness=1, 
                cursor="hand2"
            )
            card.grid(row=row, column=col, padx=5, pady=5, sticky="nsew")

            local_path = os.path.join(self.cache_dir, filename)

            lbl_thumb = tk.Label(
                card, 
                text="🖼️ Skeleton Box\n(Queued)", 
                bg="#d0d0d0", 
                fg="#555555",
                font=("Arial", 8, "italic"),
                width=18, 
                height=6
            )
            lbl_thumb.pack(fill="both", expand=True, padx=5, pady=(5, 2))

            disp_name = filename if len(filename) <= 16 else filename[:13] + "..."
            lbl_name = tk.Label(card, text=f"{idx+1}. {disp_name}", bg="#e8e8e8", font=("Arial", 8))
            lbl_name.pack(fill="x", padx=5, pady=(0, 5))

            for widget in (card, lbl_thumb, lbl_name):
                widget.bind("<Button-1>", lambda e, i=idx: self._on_card_click(i))

            self.grid_widgets.append({"card": card, "thumb": lbl_thumb, "name": lbl_name})

            if os.path.exists(local_path):
                self._update_card_thumbnail(idx, local_path)

    def _background_download_worker(self, idx):
        if self.stop_bg_tasks or idx >= len(self.image_items):
            return

        filename, node, dec_a32 = self.image_items[idx]
        local_path = os.path.join(self.cache_dir, filename)

        if not os.path.exists(local_path):
            try:
                self.root.after(0, lambda i=idx: self._set_card_status_text(i, "⏳ Streaming..."))
                download_and_decrypt_stream(self.folder_id, node, dec_a32, local_path)
            except Exception:
                return

        if os.path.exists(local_path) and not self.stop_bg_tasks:
            self.root.after(0, lambda i=idx, p=local_path: self._on_bg_item_ready(i, p))

    def _set_card_status_text(self, idx, text):
        if idx < len(self.grid_widgets) and idx not in self.thumbnail_objs:
            self.grid_widgets[idx]["thumb"].config(text=text)

    def _on_bg_item_ready(self, idx, local_path):
        self._update_card_thumbnail(idx, local_path)
        if idx == self.current_index:
            self.render_image(local_path)

    def _update_card_thumbnail(self, idx, local_path):
        try:
            pil_img = Image.open(local_path)
            pil_img.thumbnail((110, 110), Image.Resampling.LANCZOS)
            photo = ImageTk.PhotoImage(pil_img)
            self.thumbnail_objs[idx] = photo

            lbl = self.grid_widgets[idx]["thumb"]
            lbl.config(image=photo, text="", bg="#ffffff")
        except Exception:
            pass

    def _on_card_click(self, index):
        self._load_image_by_index(index)

    def _highlight_selected_card(self, index):
        for idx, item in enumerate(self.grid_widgets):
            card = item["card"]
            if idx == index:
                card.config(highlightbackground="#007acc", highlightthickness=2, bg="#d9eefd")
                item["name"].config(bg="#d9eefd", font=("Arial", 8, "bold"))
            else:
                card.config(highlightbackground="#cccccc", highlightthickness=1, bg="#e8e8e8")
                item["name"].config(bg="#e8e8e8", font=("Arial", 8))

    def _load_image_by_index(self, index):
        if index < 0 or index >= len(self.image_items):
            return

        self.current_index = index
        self._highlight_selected_card(index)

        filename, node, dec_a32 = self.image_items[index]
        local_path = os.path.join(self.cache_dir, filename)

        total = len(self.image_items)
        self.counter_var.set(f"{index + 1} / {total}")
        self.btn_prev.config(state="normal" if index > 0 else "disabled")
        self.btn_next.config(state="normal" if index < total - 1 else "disabled")

        if os.path.exists(local_path):
            self.status_var.set(f"Viewing: {filename}")
            self._update_card_thumbnail(index, local_path)
            self.render_image(local_path)
            return

        self.status_var.set(f"Priority Streaming ({index + 1}/{total}): {filename}...")
        self.img_label.config(image="", text=f"Priority Streaming {filename}...\nPlease wait.")
        self._set_card_status_text(index, "⏳ Priority Stream...")

        # Immediate priority thread for selected card
        threading.Thread(
            target=self._priority_download_worker, 
            args=(index, node, dec_a32, filename, local_path), 
            daemon=True
        ).start()

    def _priority_download_worker(self, index, node, dec_a32, filename, local_path):
        try:
            download_and_decrypt_stream(self.folder_id, node, dec_a32, local_path)
            self.root.after(0, lambda: self._on_bg_item_ready(index, local_path))
        except Exception as err:
            err_msg = f"Error streaming file:\n{err}"
            self.root.after(0, self._on_error, err_msg)

    def render_image(self, file_path):
        try:
            pil_img = Image.open(file_path)

            box_w = max(self.image_container.winfo_width() - 20, 200)
            box_h = max(self.image_container.winfo_height() - 20, 200)

            pil_img.thumbnail((box_w, box_h), Image.Resampling.LANCZOS)

            self.current_img_obj = ImageTk.PhotoImage(pil_img)
            self.img_label.config(image=self.current_img_obj, text="")
        except Exception as e:
            self.img_label.config(image="", text=f"Error rendering image:\n{e}")

    def show_prev_image(self):
        if self.current_index > 0:
            self._load_image_by_index(self.current_index - 1)

    def show_next_image(self):
        if self.current_index < len(self.image_items) - 1:
            self._load_image_by_index(self.current_index + 1)

    def _on_window_resize(self, event):
        if self.current_index >= 0 and self.image_items:
            filename, _, _ = self.image_items[self.current_index]
            local_path = os.path.join(self.cache_dir, filename)
            if os.path.exists(local_path):
                self.render_image(local_path)

    def _on_error(self, message):
        self.btn_fetch.config(state="normal")
        self.status_var.set("An error occurred.")
        messagebox.showerror("Error", message)


if __name__ == "__main__":
    USER_MEGA_URL = "https://mega.nz/folder/x1xlySjR#R1VnF2fFM4axCxHNhFS-CQ"

    root = tk.Tk()
    app = ReliableMegaGridViewerApp(root, initial_url=USER_MEGA_URL)
    root.mainloop()