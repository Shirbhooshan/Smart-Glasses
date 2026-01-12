"""
ESP32-CAM QR Code Scanner with Web Integration
Monitors Firebase, detects QR codes, opens URLs, and sends data to website
"""

import cv2
import numpy as np
import requests
import base64
import webbrowser
import time
import json
from pyzbar import pyzbar
from urllib.parse import urlparse
from datetime import datetime

# Firebase Configuration
FIREBASE_URL = "https://smart-glasses-ff6d1-default-rtdb.asia-southeast1.firebasedatabase.app"
CAMERA_PATH = "/smartglasses/camera.json"
QR_RESULTS_PATH = "/smartglasses/qr_detections"

# Settings
CHECK_INTERVAL = 0.5
SHOW_PREVIEW = True
SCAN_COOLDOWN = 5
AUTO_OPEN_URLS = True  # Automatically open URLs in browser
SEND_TO_WEB = True     # Send QR data to website

recent_qrs = {}

def fetch_camera_frame():
    """Fetch the latest camera frame from Firebase"""
    try:
        response = requests.get(f"{FIREBASE_URL}{CAMERA_PATH}")
        if response.status_code == 200:
            data = response.json()
            if data and 'frame' in data:
                return data['frame'], data.get('timestamp', 'unknown')
        return None, None
    except Exception as e:
        print(f"❌ Error fetching frame: {e}")
        return None, None

def decode_frame(base64_string):
    """Decode base64 image to OpenCV format"""
    try:
        if ',' in base64_string:
            base64_string = base64_string.split(',')[1]
        img_bytes = base64.b64decode(base64_string)
        nparr = np.frombuffer(img_bytes, np.uint8)
        img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
        return img
    except Exception as e:
        print(f"❌ Error decoding frame: {e}")
        return None

def scan_qr_codes(frame):
    """Scan frame for QR codes"""
    qr_codes = []
    detected_codes = pyzbar.decode(frame)
    
    for code in detected_codes:
        qr_data = code.data.decode('utf-8')
        qr_type = code.type
        rect = code.rect
        polygon = code.polygon
        
        qr_codes.append({
            'data': qr_data,
            'type': qr_type,
            'rect': rect,
            'polygon': polygon
        })
    
    return qr_codes

def draw_qr_codes(frame, qr_codes):
    """Draw boxes around detected QR codes"""
    for qr in qr_codes:
        points = qr['polygon']
        if len(points) == 4:
            pts = np.array([[p.x, p.y] for p in points], np.int32)
            pts = pts.reshape((-1, 1, 2))
            cv2.polylines(frame, [pts], True, (0, 255, 0), 3)
        
        rect = qr['rect']
        cv2.rectangle(frame, (rect.left, rect.top), 
                     (rect.left + rect.width, rect.top + rect.height),
                     (0, 255, 0), 2)
        
        # Truncate long text
        display_text = qr['data'][:40] + "..." if len(qr['data']) > 40 else qr['data']
        text = f"{qr['type']}: {display_text}"
        cv2.putText(frame, text, (rect.left, rect.top - 10),
                   cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 2)
    
    return frame

def is_valid_url(url):
    """Check if string is a valid URL"""
    try:
        result = urlparse(url)
        return all([result.scheme, result.netloc])
    except:
        return False

def open_url(url):
    """Open URL in default browser"""
    try:
        webbrowser.open(url)
        print(f"✓ Opened in browser: {url}")
        return True
    except Exception as e:
        print(f"❌ Failed to open URL: {e}")
        return False

def send_to_website(qr_data, qr_type, is_url):
    """Send QR detection data to Firebase for website display"""
    try:
        timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        
        payload = {
            "data": qr_data,
            "type": qr_type,
            "is_url": is_url,
            "timestamp": timestamp,
            "status": "opened" if (is_url and AUTO_OPEN_URLS) else "detected"
        }
        
        # Push to Firebase (creates unique key)
        response = requests.post(
            f"{FIREBASE_URL}{QR_RESULTS_PATH}.json",
            json=payload
        )
        
        if response.status_code == 200:
            print(f"✓ Sent to website: {qr_data[:50]}")
            return True
        else:
            print(f"❌ Failed to send to website: {response.status_code}")
            return False
            
    except Exception as e:
        print(f"❌ Error sending to website: {e}")
        return False

def should_process_qr(qr_data):
    """Check if we should process this QR"""
    current_time = time.time()
    
    if qr_data in recent_qrs:
        last_scan_time = recent_qrs[qr_data]
        if current_time - last_scan_time < SCAN_COOLDOWN:
            return False
    
    recent_qrs[qr_data] = current_time
    return True

def clean_old_qrs():
    """Remove old entries from recent_qrs"""
    current_time = time.time()
    expired_qrs = [qr for qr, timestamp in recent_qrs.items() 
                   if current_time - timestamp > SCAN_COOLDOWN * 2]
    for qr in expired_qrs:
        del recent_qrs[qr]

def main():
    """Main scanner loop"""
    print("=" * 70)
    print("🎥 ESP32-CAM QR Code Scanner with Web Integration")
    print("=" * 70)
    print(f"📡 Monitoring: {FIREBASE_URL}{CAMERA_PATH}")
    print(f"⏱️  Check interval: {CHECK_INTERVAL}s")
    print(f"🔍 Preview: {'Enabled' if SHOW_PREVIEW else 'Disabled'}")
    print(f"🌐 Auto-open URLs: {'Enabled' if AUTO_OPEN_URLS else 'Disabled'}")
    print(f"📤 Send to website: {'Enabled' if SEND_TO_WEB else 'Disabled'}")
    print(f"⏳ Scan cooldown: {SCAN_COOLDOWN}s")
    print("=" * 70)
    print("\n👁️  Waiting for camera frames...\n")
    
    last_timestamp = None
    frame_count = 0
    qr_count = 0
    
    try:
        while True:
            frame_base64, timestamp = fetch_camera_frame()
            
            if frame_base64 and timestamp != last_timestamp:
                frame_count += 1
                last_timestamp = timestamp
                
                frame = decode_frame(frame_base64)
                
                if frame is not None:
                    qr_codes = scan_qr_codes(frame)
                    
                    if qr_codes:
                        print(f"\n🔍 [{timestamp}] Found {len(qr_codes)} QR code(s)!")
                        
                        for qr in qr_codes:
                            qr_data = qr['data']
                            
                            if not should_process_qr(qr_data):
                                print(f"⏭️  Skipping (recently scanned)")
                                continue
                            
                            qr_count += 1
                            print(f"\n{'='*60}")
                            print(f"QR #{qr_count}")
                            print(f"{'='*60}")
                            print(f"📋 Type: {qr['type']}")
                            print(f"📝 Data: {qr_data}")
                            
                            is_url = is_valid_url(qr_data)
                            
                            if is_url:
                                print(f"🌐 Detected: URL")
                                if AUTO_OPEN_URLS:
                                    open_url(qr_data)
                            else:
                                print(f"ℹ️  Detected: Text/Data")
                            
                            if SEND_TO_WEB:
                                send_to_website(qr_data, qr['type'], is_url)
                            
                            print(f"{'='*60}\n")
                        
                        frame = draw_qr_codes(frame, qr_codes)
                    else:
                        if frame_count % 20 == 0:
                            print(f"⏳ [{timestamp}] Scanning... (frame {frame_count})")
                    
                    if SHOW_PREVIEW:
                        # Add status overlay
                        status = f"Frames: {frame_count} | QR Detected: {qr_count}"
                        cv2.putText(frame, status, (10, 30),
                                   cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 0), 2)
                        
                        # Add instructions
                        cv2.putText(frame, "Press 'Q' to quit", (10, frame.shape[0] - 10),
                                   cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1)
                        
                        cv2.imshow('ESP32-CAM QR Scanner', frame)
                        
                        if cv2.waitKey(1) & 0xFF == ord('q'):
                            print("\n👋 Quitting...")
                            break
            
            if frame_count % 100 == 0:
                clean_old_qrs()
            
            time.sleep(CHECK_INTERVAL)
    
    except KeyboardInterrupt:
        print("\n\n⚠️  Interrupted by user")
    
    finally:
        if SHOW_PREVIEW:
            cv2.destroyAllWindows()
        print(f"\n✅ Scanner stopped")
        print(f"📊 Statistics:")
        print(f"   - Total frames: {frame_count}")
        print(f"   - QR codes detected: {qr_count}")

if __name__ == "__main__":
    main()