exec(open('tools/read_poke_params.py').read().split('for index,')[0])
manager = b.u64(base + 0x3D667A8)
for sample in range(2):
    data = b.read(manager, 0x280)
    print('manager', hex(manager), 'pools', struct.unpack_from('<8Q', data), 'counts', struct.unpack_from('<8I', data, 0x1c0))
    node = struct.unpack_from('<Q', data, 0x28)[0]
    for i in range(3):
        if not node: break
        obj = b.read(node, 0x9d0)
        print('emitter', hex(node), obj[0x328:0x32b].hex(), 'bullet', struct.unpack_from('<i', obj, 0x334)[0], 'timer', struct.unpack_from('<f', obj, 0x990)[0], 'owner', obj[0x340:0x348].hex(), 'position',struct.unpack_from('<4f',obj,0x3b0))
        node = struct.unpack_from('<Q', obj, 0x998)[0]
    import time
    time.sleep(1)
