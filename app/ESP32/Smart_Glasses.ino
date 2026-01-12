#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SH110X.h>
#include <DHT.h>
#include <BluetoothSerial.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <time.h>

// ==================== CONFIG ====================

#define WIFI_SSID "Islington College"
#define WIFI_PASSWORD "I$LiNGT0N2026"
#define FIREBASE_HOST "https://smart-glasses-ff6d1-default-rtdb.asia-southeast1.firebasedatabase.app"

#define DHTPIN 4
#define TOUCH_PIN 14
#define BUZZER_PIN 15
#define OLED_RESET -1
#define DHTTYPE DHT11

// Timing - FIXED: Longer notification time
#define WEB_MSG_TIME 20000
#define NOTIF_TIME 60000        // ← INCREASED to 60 seconds (was causing early disappearance)
#define SENSOR_INT 3000
#define FB_INT 15000
#define WIFI_INT 120000
#define CLOCK_INT 1000
#define TOUCH_DEB 300
#define WEB_CHECK_INT 30000

#define BUZZER_FREQ 2000

// ==================== OBJECTS ====================

Adafruit_SH1106G display(128, 64, &Wire, OLED_RESET);
DHT dht(DHTPIN, DHTTYPE);
BluetoothSerial SerialBT;

// ==================== STATE ====================

enum State { INIT, CLOCK, SENSOR, PHONE_NOTIF, WEB_MSG };
State state = INIT, prevState = CLOCK;

String phoneMsg = "", phoneType = "", phoneFrom = "";
String webMsg = "";
String btBuf = "";
String lastRawMsg = "";

unsigned long phoneStart = 0, webStart = 0;
unsigned long lastSensor = 0, lastFB = 0, lastClock = 0, lastWifi = 0, lastWebCheck = 0;

float temp = 0, hum = 0;
int notifCnt = 0;  // ← ADDED: Was missing, causing compilation error
bool wifiOK = false, timeOK = false;
bool btConnected = false;
struct tm timeinfo;

// ==================== BUZZER STATE ====================

unsigned long buzzerStart = 0;
int buzzerStep = 0;
String buzzerMode = "";

// ==================== BUZZER ====================

void startBuzzer(String mode) {
  buzzerMode = mode;
  buzzerStep = 0;
  buzzerStart = millis();
  Serial.println("🔊 Starting buzzer: " + mode);
}

void handleBuzzer() {
  if (buzzerMode == "") return;
  unsigned long now = millis();

  if (buzzerMode == "STARTUP" || buzzerMode == "CONNECT") {
    if (buzzerStep == 0) {
      tone(BUZZER_PIN, BUZZER_FREQ, 120);
      buzzerStep++;
    } else {
      buzzerMode = "";
    }
  }

  if (buzzerMode == "CALL") {
    // 3 long beeps for calls
    if (buzzerStep < 3 && now - buzzerStart > buzzerStep * 450) {
      tone(BUZZER_PIN, BUZZER_FREQ, 300);
      buzzerStep++;
    } else if (buzzerStep >= 3) {
      buzzerMode = "";
    }
  }

  if (buzzerMode == "MSG") {
    // 2 short beeps for messages
    if (buzzerStep < 2 && now - buzzerStart > buzzerStep * 250) {
      tone(BUZZER_PIN, BUZZER_FREQ, 100);
      buzzerStep++;
    } else if (buzzerStep >= 2) {
      buzzerMode = "";
    }
  }

  if (buzzerMode == "NOTIF") {
    // 1 medium beep for notifications
    if (buzzerStep == 0) {
      tone(BUZZER_PIN, BUZZER_FREQ, 150);
      buzzerStep++;
    } else {
      buzzerMode = "";
    }
  }
}

// ==================== BLUETOOTH CALLBACK ====================

void btCallback(esp_spp_cb_event_t event, esp_spp_cb_param_t *param) {
  if (event == ESP_SPP_SRV_OPEN_EVT) {
    Serial.println("=================================");
    Serial.println("Bluetooth Client Connected!");
    Serial.println("=================================");
    btConnected = true;
    showBluetoothConnected();
    startBuzzer("CONNECT");
  }
  if (event == ESP_SPP_CLOSE_EVT) {
    Serial.println("=================================");
    Serial.println("Bluetooth Client Disconnected!");
    Serial.println("=================================");
    btConnected = false;
  }
}

// ==================== SETUP ====================

void setup() {
  Serial.begin(115200);
  Wire.begin();

  if (!display.begin(0x3C, true)) {
    Serial.println("OLED fail");
    while (1) delay(1);
  }

  showStartup();
  dht.begin();

  pinMode(TOUCH_PIN, INPUT);
  pinMode(BUZZER_PIN, OUTPUT);

  startBuzzer("STARTUP");

  SerialBT.begin("ESP32_Glasses");
  SerialBT.register_callback(btCallback);
  Serial.println("BT ready: ESP32_Glasses");

  connectWiFi();
  if (wifiOK) {
    setupTime();
    showWiFiOK();
    startBuzzer("CONNECT");
  } else {
    showWiFiFail();
  }

  Serial.println("System Ready!");
  state = CLOCK;
}

// ==================== LOOP ====================

void loop() {
  unsigned long now = millis();
  
  handleBuzzer();

  // Bluetooth message reading
  while (SerialBT.available()) {
    char c = SerialBT.read();
    Serial.print(c);  // Debug: print each character
    
    if (c == '\n' || c == '\r') {
      if (btBuf.length() > 0) {
        Serial.println("\n=== RECEIVED MESSAGE ===");
        Serial.print("Raw: '");
        Serial.print(btBuf);
        Serial.println("'");
        Serial.print("Length: ");
        Serial.println(btBuf.length());
        
        processPhone(btBuf);
        btBuf = "";
      }
    } else if (c >= 32 && c <= 126 && btBuf.length() < 200) {
      btBuf += c;
    }
  }

  // Web messages check
  if (wifiOK && now - lastWebCheck > WEB_CHECK_INT) {
    checkWeb();
    lastWebCheck = now;
  }

  // Sensor reading
  if (now - lastSensor > SENSOR_INT) {
    temp = dht.readTemperature();
    hum = dht.readHumidity();
    if (isnan(temp)) temp = 0;
    if (isnan(hum)) hum = 0;
    lastSensor = now;
  }

  // Firebase upload
  if (wifiOK && now - lastFB > FB_INT) {
    uploadData();
    lastFB = now;
  }

  // Touch handling (only in CLOCK/SENSOR modes)
  if (state == CLOCK || state == SENSOR) {
    handleTouch(now);
  }

  // Notification timeout
  if (state == PHONE_NOTIF && now - phoneStart > NOTIF_TIME) {
    Serial.println("Notification timeout - returning to " + String(prevState == CLOCK ? "CLOCK" : "SENSOR"));
    state = prevState;
    display.clearDisplay();
  }

  // Web message timeout
  if (state == WEB_MSG && now - webStart > WEB_MSG_TIME) {
    Serial.println("Web message timeout - returning to CLOCK");
    state = CLOCK;
    webMsg = "";
    display.clearDisplay();
  }

  updateDisplay(now);
  delay(10);
}

// ==================== TOUCH ====================

void handleTouch(unsigned long now) {
  static bool lastTouchState = LOW;
  static unsigned long lastTouchTime = 0;
  
  int touch = digitalRead(TOUCH_PIN);
  
  if (touch == HIGH && lastTouchState == LOW && (now - lastTouchTime > TOUCH_DEB)) {
    lastTouchTime = now;
    tone(BUZZER_PIN, BUZZER_FREQ, 50);  // Small beep
    
    if (state == CLOCK) {
      prevState = CLOCK;
      state = SENSOR;
      Serial.println("Touch: CLOCK → SENSOR");
    } else if (state == SENSOR) {
      prevState = SENSOR;
      state = CLOCK;
      Serial.println("Touch: SENSOR → CLOCK");
    }
    display.clearDisplay();
  }
  lastTouchState = touch;
}

// ==================== PHONE PARSING ====================

void processPhone(String msg) {
  msg.trim();
  
  // Prevent duplicate processing
  if (msg == lastRawMsg) {
    Serial.println("⏭️  Duplicate message, skipping");
    return;
  }
  lastRawMsg = msg;

  Serial.println("=== PARSING MESSAGE ===");
  Serial.println("Message: '" + msg + "'");

  // Reset values
  phoneType = "";
  phoneFrom = "";
  phoneMsg = "";

  // Parse: TYPE:FROM:MESSAGE or TYPE:MESSAGE
  int firstColon = msg.indexOf(':');
  if (firstColon == -1) {
    Serial.println("ERROR: No colon found");
    return;
  }

  String type = msg.substring(0, firstColon);
  type.trim();
  Serial.println("Type: '" + type + "'");

  if (type == "CALL") {
    phoneType = "CALL";
    phoneFrom = msg.substring(firstColon + 1);
    phoneFrom.trim();
    phoneMsg = phoneFrom;
    Serial.println("✓ CALL from: '" + phoneFrom + "'");
    
  } else if (type == "MSG") {
    phoneType = "MSG";
    int secondColon = msg.indexOf(':', firstColon + 1);
    if (secondColon != -1) {
      phoneFrom = msg.substring(firstColon + 1, secondColon);
      phoneFrom.trim();
      phoneMsg = msg.substring(secondColon + 1);
      phoneMsg.trim();
    } else {
      phoneFrom = "";
      phoneMsg = msg.substring(firstColon + 1);
      phoneMsg.trim();
    }
    Serial.println("✓ MSG from: '" + phoneFrom + "' msg: '" + phoneMsg + "'");
    
  } else if (type == "NOTIF") {
    phoneType = "NOTIF";
    int secondColon = msg.indexOf(':', firstColon + 1);
    if (secondColon != -1) {
      phoneFrom = msg.substring(firstColon + 1, secondColon);
      phoneFrom.trim();
      phoneMsg = msg.substring(secondColon + 1);
      phoneMsg.trim();
    } else {
      phoneFrom = "";
      phoneMsg = msg.substring(firstColon + 1);
      phoneMsg.trim();
    }
    Serial.println("✓ NOTIF from: '" + phoneFrom + "' msg: '" + phoneMsg + "'");
    
  } else if (type == "TEST") {
    phoneType = "TEST";
    phoneFrom = "";
    phoneMsg = msg.substring(firstColon + 1);
    phoneMsg.trim();
    Serial.println("✓ TEST: '" + phoneMsg + "'");
    
  } else {
    Serial.println("⚠ Unknown type, treating as notification");
    phoneType = "NOTIF";
    phoneFrom = "";
    phoneMsg = msg;
  }

  Serial.println("======================");

  // Remember current state
  if (state != PHONE_NOTIF && state != WEB_MSG) {
    prevState = state;
  }

  // Switch to notification state
  state = PHONE_NOTIF;
  phoneStart = millis();
  display.clearDisplay();

  // Display FIRST (critical!)
  showPhone();

  // Then trigger buzzer
  if (phoneType == "CALL") {
    startBuzzer("CALL");
  } else if (phoneType == "MSG") {
    startBuzzer("MSG");
  } else {
    startBuzzer("NOTIF");
  }

  // Upload to Firebase
  uploadNotif();
}

// ==================== WEB MESSAGES ====================

void checkWeb() {
  HTTPClient http;
  http.begin(String(FIREBASE_HOST) + "/smartglasses/webMessage.json");
  http.setTimeout(1000);
  
  int code = http.GET();
  if (code == 200) {
    String payload = http.getString();
    payload.trim();
    payload.replace("\"", "");
    
    if (payload.length() > 2 && payload != "null" && payload != webMsg) {
      webMsg = payload;
      
      if (state != PHONE_NOTIF && state != WEB_MSG) {
        prevState = state;
      }
      
      state = WEB_MSG;
      webStart = millis();
      display.clearDisplay();
      
      Serial.println("Web Message: " + webMsg);
      
      showWeb();
      startBuzzer("NOTIF");
      clearWebMessage();
    }
  }
  http.end();
}

void clearWebMessage() {
  HTTPClient http;
  http.begin(String(FIREBASE_HOST) + "/smartglasses/webMessage.json");
  http.PUT("null");
  http.end();
}

// ==================== DISPLAY ====================

void updateDisplay(unsigned long now) {
  switch (state) {
    case CLOCK:
      if (now - lastClock >= CLOCK_INT) {
        showClock();
        lastClock = now;
      }
      break;
    case SENSOR:
      showSensor();
      break;
    case PHONE_NOTIF:
      // Keep showing phone notification (don't update constantly)
      break;
    case WEB_MSG:
      // Keep showing web message (don't update constantly)
      break;
  }
}

void showClock() {
  display.clearDisplay();

  int h, m, s;
  String day = "---", date = "---";
  
  if (timeOK && getLocalTime(&timeinfo)) {
    h = timeinfo.tm_hour;
    m = timeinfo.tm_min;
    s = timeinfo.tm_sec;
    
    const char* days[] = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    day = String(days[timeinfo.tm_wday]);
    
    const char* months[] = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", 
                            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    date = String(months[timeinfo.tm_mon]) + " " + String(timeinfo.tm_mday);
  } else {
    unsigned long sec = millis() / 1000;
    h = (sec / 3600) % 24;
    m = (sec / 60) % 60;
    s = sec % 60;
    date = "Offline";
  }

  display.setTextSize(1);
  String topLine = day + " | " + date;
  int topWidth = topLine.length() * 6;
  display.setCursor((128 - topWidth) / 2, 5);
  display.print(topLine);

  display.setTextSize(2);
  char timeStr[9];
  sprintf(timeStr, "%02d:%02d:%02d", h, m, s);
  int timeWidth = strlen(timeStr) * 12;
  display.setCursor((128 - timeWidth) / 2, 30);
  display.print(timeStr);

  display.display();
}

void showSensor() {
  display.clearDisplay();
  
  display.fillCircle(20, 50, 8, SH110X_WHITE);
  display.fillRect(17, 20, 6, 30, SH110X_WHITE);
  display.drawCircle(20, 20, 3, SH110X_WHITE);
  
  display.setTextSize(1);
  display.setCursor(40, 15);
  display.print("Temperature");
  
  display.setTextSize(3);
  display.setCursor(45, 30);
  display.print(temp, 0);
  
  display.setTextSize(1);
  display.setCursor(100, 35);
  display.print("o");
  display.setCursor(107, 38);
  display.print("C");
  
  display.drawLine(0, 56, 128, 56, SH110X_WHITE);
  
  display.setTextSize(1);
  display.setCursor(35, 58);
  display.print("Humidity: ");
  display.print(hum, 0);
  display.print("%");

  display.display();
}

void showPhone() {
  display.clearDisplay();
  
  Serial.println("📱 Displaying on OLED - Type: " + phoneType);

  if (phoneType == "CALL") {
    // Phone icon
    display.drawRoundRect(5, 5, 18, 12, 2, SH110X_WHITE);
    display.fillRect(7, 8, 4, 6, SH110X_WHITE);
    display.fillRect(15, 8, 4, 6, SH110X_WHITE);
    
    // Ringing waves
    display.drawLine(2, 7, 0, 5, SH110X_WHITE);
    display.drawLine(2, 14, 0, 16, SH110X_WHITE);
    display.drawLine(25, 7, 27, 5, SH110X_WHITE);
    display.drawLine(25, 14, 27, 16, SH110X_WHITE);
    
    display.setTextSize(1);
    display.setCursor(30, 8);
    display.print("Incoming Call");
    
    display.setTextSize(2);
    int w = phoneFrom.length() * 12;
    display.setCursor(max(0, (128 - w) / 2), 30);
    display.print(phoneFrom);
    
  } else if (phoneType == "TEST") {
    // Test icon
    display.fillCircle(12, 10, 8, SH110X_WHITE);
    display.setTextSize(1);
    display.setCursor(7, 7);
    display.setTextColor(SH110X_BLACK);
    display.print("T");
    display.setTextColor(SH110X_WHITE);
    
    display.setCursor(25, 8);
    display.print("Test Message");
    
    display.setTextSize(1);
    int y = 25;
    int maxChars = 21;
    
    for (unsigned int i = 0; i < phoneMsg.length(); i += maxChars) {
      if (y > 58) break;
      String line = phoneMsg.substring(i, min(i + maxChars, phoneMsg.length()));
      display.setCursor(0, y);
      display.print(line);
      y += 10;
    }
    
  } else {
    // Notification bell icon
    display.fillCircle(12, 8, 6, SH110X_WHITE);
    display.fillRect(9, 14, 6, 2, SH110X_WHITE);
    display.drawLine(11, 16, 11, 18, SH110X_WHITE);
    display.drawLine(13, 16, 13, 18, SH110X_WHITE);
    
    display.setTextSize(1);
    display.setCursor(25, 8);
    if (phoneType == "MSG") {
      display.print("Message");
    } else {
      display.print("Notification");
    }
    
    display.setTextSize(1);
    int y = 25;
    int maxChars = 21;
    
    String displayMsg = phoneMsg;
    if (phoneFrom.length() > 0) {
      displayMsg = phoneFrom + ": " + phoneMsg;
    }
    
    for (unsigned int i = 0; i < displayMsg.length(); i += maxChars) {
      if (y > 58) break;
      String line = displayMsg.substring(i, min(i + maxChars, displayMsg.length()));
      display.setCursor(0, y);
      display.print(line);
      y += 10;
    }
  }

  display.display();
}

void showWeb() {
  display.clearDisplay();
  
  display.drawRoundRect(5, 5, 18, 12, 2, SH110X_WHITE);
  display.fillTriangle(8, 17, 12, 20, 16, 17, SH110X_WHITE);
  
  display.setTextSize(1);
  display.setCursor(28, 8);
  display.print("Web Message");
  
  display.setTextSize(1);
  int y = 25;
  int maxChars = 21;
  
  for (unsigned int i = 0; i < webMsg.length(); i += maxChars) {
    if (y > 58) break;
    String line = webMsg.substring(i, min(i + maxChars, webMsg.length()));
    display.setCursor(0, y);
    display.print(line);
    y += 10;
  }
  
  display.display();
}

// ==================== STARTUP & WIFI ====================

void showStartup() {
  display.clearDisplay();
  display.setTextColor(SH110X_WHITE);
  display.setTextSize(2);
  display.setCursor(10, 15);
  display.print("Smart");
  display.setCursor(10, 32);
  display.print("Glasses");
  
  for (int i = 0; i < 3; i++) {
    display.fillRect(30 + (i * 20), 52, 15, 8, SH110X_WHITE);
    display.display();
    delay(300);
  }
  delay(500);
}

void showBluetoothConnected() {
  display.clearDisplay();
  display.setTextSize(1);
  
  display.fillRect(55, 10, 4, 12, SH110X_WHITE);
  display.fillTriangle(59, 10, 59, 16, 65, 13, SH110X_WHITE);
  display.fillTriangle(59, 16, 59, 22, 65, 19, SH110X_WHITE);
  display.fillTriangle(53, 13, 59, 10, 59, 16, SH110X_WHITE);
  display.fillTriangle(53, 19, 59, 16, 59, 22, SH110X_WHITE);
  
  display.setTextSize(1);
  display.setCursor(10, 30);
  display.print("Bluetooth");
  display.setCursor(25, 42);
  display.print("Connected!");
  
  display.display();
  delay(2000);
  display.clearDisplay();
}

void connectWiFi() {
  display.clearDisplay();
  display.setTextSize(1);
  display.setCursor(10, 25);
  display.print("Connecting WiFi");
  display.display();
  
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  
  int tries = 0;
  while (WiFi.status() != WL_CONNECTED && tries < 20) {
    display.setCursor(45 + (tries % 3) * 10, 35);
    display.print(".");
    display.display();
    delay(500);
    tries++;
  }

  wifiOK = (WiFi.status() == WL_CONNECTED);
  Serial.println(wifiOK ? "WiFi Connected!" : "WiFi Failed");
}

void showWiFiOK() {
  display.clearDisplay();
  display.setTextSize(1);
  display.setCursor(15, 20);
  display.print("WiFi Connected");
  display.setCursor(10, 35);
  display.print("Services Online");
  display.drawLine(50, 50, 55, 55, SH110X_WHITE);
  display.drawLine(55, 55, 70, 45, SH110X_WHITE);
  display.display();
  delay(2000);
}

void showWiFiFail() {
  display.clearDisplay();
  display.setTextSize(1);
  display.setCursor(5, 20);
  display.print("Unable to connect");
  display.setCursor(25, 30);
  display.print("to WiFi");
  display.setCursor(10, 45);
  display.print("Offline Mode");
  display.display();
  delay(2500);
}

void setupTime() {
  configTime(5 * 3600 + 45 * 60, 0, "pool.ntp.org", "time.nist.gov");
  int retries = 0;
  while (!getLocalTime(&timeinfo) && retries < 10) {
    delay(500);
    retries++;
  }
  timeOK = (retries < 10);
  if (timeOK) {
    Serial.println("Nepal time synced!");
  }
}

// ==================== FIREBASE ====================

void uploadData() {
  if (!wifiOK) return;

  HTTPClient http;
  String ts;
  if (timeOK && getLocalTime(&timeinfo)) {
    char buf[30];
    strftime(buf, sizeof(buf), "%Y-%m-%d %H:%M:%S", &timeinfo);
    ts = String(buf);
  } else {
    ts = "Offline";
  }
  
  String data = "{\"temperature\":" + String(temp, 1) + 
                ",\"humidity\":" + String(hum, 1) + 
                ",\"timestamp\":\"" + ts + "\"}";

  http.begin(String(FIREBASE_HOST) + "/smartglasses/readings.json");
  http.addHeader("Content-Type", "application/json");
  http.PUT(data);
  http.end();

  http.begin(String(FIREBASE_HOST) + "/smartglasses/status.json");
  http.PUT("\"online\"");
  http.end();
}

void uploadNotif() {
  if (!wifiOK) return;

  notifCnt++;
  HTTPClient http;
  String ts;
  if (timeOK && getLocalTime(&timeinfo)) {
    char buf[30];
    strftime(buf, sizeof(buf), "%Y-%m-%d %H:%M:%S", &timeinfo);
    ts = String(buf);
  } else {
    ts = "Offline";
  }
  
  String data = "{\"type\":\"" + phoneType + 
                "\",\"from\":\"" + phoneFrom + 
                "\",\"message\":\"" + phoneMsg + 
                "\",\"timestamp\":\"" + ts + "\"}";

  http.begin(String(FIREBASE_HOST) + "/smartglasses/notifications/notif_" + String(notifCnt) + ".json");
  http.addHeader("Content-Type", "application/json");
  http.PUT(data);
  http.end();
}