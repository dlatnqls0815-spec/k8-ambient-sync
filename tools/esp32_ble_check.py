"""PC check of the ESP32 BLE settings service (the path the phone app 0.5.0 uses).

Scans for "K8AMB-ESP32", subscribes to the TX notify characteristic and writes lines to RX one per
write with response, like EspLink.java: HELLO, STATUS, GET, then writes the board's own settings back
(GET lines + SAVE, so nothing changes) and checks each is acknowledged. No lamp or car traffic.
Usage: python tools/esp32_ble_check.py [--seconds 10]
"""
import argparse
import asyncio
import sys

try:
    from bleak import BleakClient, BleakScanner
except ImportError:  # pragma: no cover - tool dependency
    sys.exit("bleak required: python -m pip install --user bleak")

SERVICE = "6b38a0d2-4f7e-4c1b-9d5a-2e8f71c3b9e0"
RX = "6b38a0d2-4f7e-4c1b-9d5a-2e8f71c3b9e1"
TX = "6b38a0d2-4f7e-4c1b-9d5a-2e8f71c3b9e2"
NAME = "K8AMB-ESP32"


async def main(seconds):
    found = await BleakScanner.find_device_by_filter(
        lambda d, ad: (d.name or "").startswith(NAME) or SERVICE in [u.lower() for u in ad.service_uuids],
        timeout=seconds)
    if found is None:
        sys.exit("ESP32 not found over BLE")
    print("found " + NAME)
    replies = []
    event = asyncio.Event()

    def on_notify(_, data):
        for line in data.decode("utf-8", "replace").split("\n"):
            if line.strip():
                replies.append(line.strip())
        event.set()

    async with BleakClient(found) as client:
        print("connected, mtu %d" % client.mtu_size)
        await client.start_notify(TX, on_notify)

        async def ask(line, until, timeout=4.0):
            start = len(replies)
            await client.write_gatt_char(RX, line.encode("utf-8"), response=True)
            end = asyncio.get_event_loop().time() + timeout
            while asyncio.get_event_loop().time() < end:
                for reply in replies[start:]:
                    if reply.startswith(until):
                        return replies[start:]
                event.clear()
                try:
                    await asyncio.wait_for(event.wait(), 0.2)
                except asyncio.TimeoutError:
                    pass
            raise RuntimeError("no %r reply to %r; got %r" % (until, line, replies[start:][-5:]))

        hello = await ask("HELLO", "K8ESP")
        print("1. " + hello[-1])
        status = await ask("STATUS", "ST end")
        print("2. status: " + " | ".join(s for s in status if s.startswith("ST ") and s != "ST end"))
        if "ST output 1" in status:
            sys.exit("lamp output is ON; this check only runs with output off")
        cfg = [s[4:] for s in await ask("GET", "CFG end") if s.startswith("CFG ") and s != "CFG end"]
        ok = 0
        for line in cfg:
            reply = (await ask(line, ("OK", "ERR")))[-1]
            ok += reply.startswith("OK")
        saved = (await ask("SAVE", "OK SAVE"))[-1]
        print("3. wrote back %d setting lines over BLE: %d OK, %s" % (len(cfg), ok, saved))
        await client.stop_notify(TX)
    passed = ok == len(cfg) and saved == "OK SAVE"
    print("ESP32 BLE check: " + ("passed" if passed else "FAILED"))
    return 0 if passed else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--seconds", type=float, default=10.0)
    sys.exit(asyncio.run(main(parser.parse_args().seconds)))
