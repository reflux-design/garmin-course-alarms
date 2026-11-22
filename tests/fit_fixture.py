"""Independent FIT decoder for synthetic regression tests."""
import struct


def crc(data):
    value = 0
    for byte in data:
        value ^= byte
        for _ in range(8):
            value = (value >> 1) ^ (0xA001 if value & 1 else 0)
    return value


def records(data):
    if len(data) < 14 or data[0] not in (12, 14) or data[8:12] != b'.FIT':
        raise ValueError('Invalid FIT header')
    size = struct.unpack_from('<I', data, 4)[0]
    if len(data) != data[0] + size + 2 or crc(data) != 0:
        raise ValueError('FIT size or checksum mismatch')
    if data[0] == 14 and crc(data[:14]) != 0:
        raise ValueError('FIT header checksum mismatch')
    definitions, result = {}, []
    pos, end = data[0], len(data) - 2

    def take(count):
        nonlocal pos
        if count < 0 or pos + count > end:
            raise ValueError('Truncated FIT record')
        start = pos
        pos += count
        return start, data[start:pos]

    while pos < end:
        _, raw = take(1)
        header = raw[0]
        if header & 0x80:
            raise ValueError('Compressed timestamps are not supported in settings')
        local = header & 15
        if header & 0x40:
            _, raw = take(5)
            if raw[0] != 0 or raw[1] not in (0, 1):
                raise ValueError('Invalid record definition')
            order = '>' if raw[1] else '<'
            number = struct.unpack_from(order + 'H', raw, 2)[0]
            fields = []
            for _ in range(raw[4]):
                _, f = take(3)
                fields.append((f[0], f[1], f[2], None))
            if header & 0x20:
                _, count = take(1)
                for _ in range(count[0]):
                    _, f = take(3)
                    fields.append((f[0], f[1], 13, f[2]))
            definitions[local] = number, order, fields
        else:
            if local not in definitions:
                raise ValueError('Undefined local message')
            number, order, fields = definitions[local]
            record = {'number': number, 'order': order, 'fields': {}}
            for field, size, base, developer in fields:
                offset, raw = take(size)
                key = field if developer is None else (developer, field)
                if key in record['fields']:
                    raise ValueError('Duplicate field')
                record['fields'][key] = {'offset': offset, 'raw': raw, 'base': base}
            result.append(record)
    return result


def uint(record, field, size, base):
    f = record['fields'].get(field)
    if f is None or len(f['raw']) != size or f['base'] != base:
        raise ValueError(f'Unexpected schema for field {field}')
    return int.from_bytes(f['raw'], 'big' if record['order'] == '>' else 'little')
