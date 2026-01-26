#!/usr/bin/env python3
# ============================================
# Симулятор GPS трекера (Teltonika Codec 8)
# ============================================
# Использование:
#   python simulator.py --host localhost --port 5001 --imei 123456789012345
#
# Симулирует:
# 1. Подключение по TCP
# 2. Отправку IMEI
# 3. Ожидание ACK
# 4. Периодическую отправку GPS точек
# ============================================

import socket
import struct
import time
import argparse
import random
from datetime import datetime

def calculate_crc16(data: bytes) -> int:
    """Вычисляет CRC-16 для Teltonika"""
    crc = 0
    for byte in data:
        crc ^= byte
        for _ in range(8):
            if crc & 1:
                crc = (crc >> 1) ^ 0xA001
            else:
                crc >>= 1
    return crc

def create_imei_packet(imei: str) -> bytes:
    """Создаёт пакет с IMEI для аутентификации"""
    imei_bytes = imei.encode('ascii')
    return struct.pack('>H', len(imei_bytes)) + imei_bytes

def create_avl_packet(latitude: float, longitude: float, speed: int = 0, satellites: int = 12) -> bytes:
    """Создаёт AVL пакет с GPS данными (Codec 8)"""
    # Timestamp (текущее время в миллисекундах)
    timestamp = int(datetime.now().timestamp() * 1000)
    
    # Координаты в формате Teltonika (degrees * 10^7)
    lat_int = int(latitude * 10_000_000)
    lon_int = int(longitude * 10_000_000)
    
    # AVL Record
    avl_record = struct.pack(
        '>qBiiHHBH',
        timestamp,      # 8 bytes - timestamp
        1,              # 1 byte - priority (High)
        lon_int,        # 4 bytes - longitude
        lat_int,        # 4 bytes - latitude
        100,            # 2 bytes - altitude (meters)
        0,              # 2 bytes - angle (degrees)
        satellites,     # 1 byte - satellites
        speed           # 2 bytes - speed (km/h)
    )
    
    # IO Elements (минимальный набор)
    io_elements = struct.pack(
        '>BBBBBB',
        0,    # Event IO ID
        0,    # Total IO count
        0,    # Count of 1-byte IO
        0,    # Count of 2-byte IO
        0,    # Count of 4-byte IO
        0     # Count of 8-byte IO
    )
    
    avl_record += io_elements
    
    # Формируем полный пакет
    codec_id = 0x08
    record_count = 1
    
    avl_data = struct.pack('BB', codec_id, record_count) + avl_record + struct.pack('B', record_count)
    
    # CRC-16
    crc = calculate_crc16(avl_data)
    
    # Preamble (4 bytes zeros) + Data length (4 bytes) + AVL data + CRC (4 bytes)
    packet = struct.pack('>I', 0) + struct.pack('>I', len(avl_data)) + avl_data + struct.pack('>I', crc)
    
    return packet

def main():
    parser = argparse.ArgumentParser(description='Симулятор GPS трекера Teltonika')
    parser.add_argument('--host', default='localhost', help='Адрес сервера')
    parser.add_argument('--port', type=int, default=5001, help='Порт сервера')
    parser.add_argument('--imei', default='123456789012345', help='IMEI трекера')
    parser.add_argument('--interval', type=int, default=10, help='Интервал отправки (секунды)')
    parser.add_argument('--count', type=int, default=0, help='Количество точек (0 = бесконечно)')
    args = parser.parse_args()
    
    # Начальные координаты (Москва)
    lat = 55.7558
    lon = 37.6173
    
    print(f"=== Симулятор трекера Teltonika ===")
    print(f"IMEI: {args.imei}")
    print(f"Сервер: {args.host}:{args.port}")
    print(f"Интервал: {args.interval}с")
    print()
    
    try:
        # Подключаемся
        print(f"[{datetime.now().isoformat()}] Подключение к {args.host}:{args.port}...")
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(30)
        sock.connect((args.host, args.port))
        print(f"[{datetime.now().isoformat()}] ✓ Подключено")
        
        # Отправляем IMEI
        imei_packet = create_imei_packet(args.imei)
        sock.send(imei_packet)
        print(f"[{datetime.now().isoformat()}] → Отправлен IMEI: {args.imei}")
        
        # Ждём ACK
        ack = sock.recv(1)
        if ack == b'\x01':
            print(f"[{datetime.now().isoformat()}] ← ACK получен (устройство принято)")
        else:
            print(f"[{datetime.now().isoformat()}] ✗ NACK получен (устройство отклонено)")
            return
        
        # Отправляем GPS точки
        sent = 0
        while args.count == 0 or sent < args.count:
            # Симулируем движение
            lat += random.uniform(-0.001, 0.001)
            lon += random.uniform(-0.001, 0.001)
            speed = random.randint(0, 60)
            
            avl_packet = create_avl_packet(lat, lon, speed)
            sock.send(avl_packet)
            
            # Ждём ACK с количеством принятых записей
            ack = sock.recv(4)
            accepted = struct.unpack('>I', ack)[0]
            
            sent += 1
            print(f"[{datetime.now().isoformat()}] → Точка #{sent}: lat={lat:.6f}, lon={lon:.6f}, speed={speed}км/ч")
            print(f"[{datetime.now().isoformat()}] ← Принято записей: {accepted}")
            
            time.sleep(args.interval)
        
        print(f"\n[{datetime.now().isoformat()}] Завершено. Отправлено точек: {sent}")
        
    except socket.timeout:
        print(f"[{datetime.now().isoformat()}] ✗ Таймаут соединения")
    except ConnectionRefusedError:
        print(f"[{datetime.now().isoformat()}] ✗ Соединение отклонено")
    except KeyboardInterrupt:
        print(f"\n[{datetime.now().isoformat()}] Прервано пользователем")
    finally:
        sock.close()

if __name__ == '__main__':
    main()
