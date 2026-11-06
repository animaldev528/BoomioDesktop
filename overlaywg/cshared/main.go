// Command cshared exposes package overlaywg as a C shared library for the desktop client.
//
// ⚠️ **This is a second binding over the same Go code, not a fork of it.** The Android client
// consumes `overlaywg` through `gomobile bind` (an AAR with generated Java). Desktop has no
// gomobile equivalent — it is a plain JVM application — so it loads the same package as a
// `-buildmode=c-shared` library through JNA. Everything that matters (keygen, the netstack
// device, the connection table, the -1 end-of-stream sentinel) stays in `overlaywg.go`; this
// file only translates calling conventions, and must not grow behaviour of its own.
//
// # The C ABI
//
//   - Functions returning `char*` return a heap string the caller **must** release with
//     `OverlayWg_Free`, or NULL where the Go function has no string to report.
//   - `OverlayWg_Up` / `OverlayWg_Down` return NULL on success and an error message on failure.
//   - `OverlayWg_Dial` writes the connection handle to an out-parameter and returns NULL on
//     success or an error message on failure. (It does **not** use the -1 sentinel plus
//     `LastError`: the Go package only remembers dial *transport* failures, so a dial attempted
//     before the tunnel is up would report "no reason recorded" and lose the one message that
//     explains it.)
//   - `OverlayWg_Read` / `OverlayWg_Write` take `(buffer, offset, length)` exactly as the Java
//     natives do, and `Read` returns **-1** at end of stream. They never throw and never fail
//     for any reason other than the stream ending.
//   - `OverlayWg_Close` returns 0 on success, -1 on error.
//
// A note on the buffer calls: the caller's memory is C memory, and a Go slice taken over it with
// `unsafe.Slice` is valid for the duration of the call only. Nothing here retains that slice —
// the underlying `overlaywg.Read`/`Write` use it and return — so the pointer never outlives the
// call, which is the condition cgo requires.
package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"strings"
	"unsafe"

	"overlaywg"
)

func main() {}

// OverlayWg_GenerateKeypair returns the four encodings joined by "|":
// privateBase64|publicBase64|privateHex|publicHex, or NULL on failure.
//
//export OverlayWg_GenerateKeypair
func OverlayWg_GenerateKeypair() *C.char {
	keypair, err := overlaywg.GenerateKeypair()
	if err != nil {
		return nil
	}
	return C.CString(strings.Join([]string{
		keypair.PrivateKeyB64,
		keypair.PublicKeyB64,
		keypair.PrivateKeyHex,
		keypair.PublicKeyHex,
	}, "|"))
}

//export OverlayWg_Up
func OverlayWg_Up(
	privateKeyHex *C.char,
	peerPublicKeyHex *C.char,
	endpoint *C.char,
	localCIDR *C.char,
	allowedIPs *C.char,
	dns *C.char,
	mtu C.int,
	keepalive C.int,
) *C.char {
	err := overlaywg.Up(
		C.GoString(privateKeyHex),
		C.GoString(peerPublicKeyHex),
		C.GoString(endpoint),
		C.GoString(localCIDR),
		C.GoString(allowedIPs),
		C.GoString(dns),
		int(mtu),
		int(keepalive),
	)
	if err != nil {
		return C.CString(err.Error())
	}
	return nil
}

//export OverlayWg_Down
func OverlayWg_Down() *C.char {
	if err := overlaywg.Down(); err != nil {
		return C.CString(err.Error())
	}
	return nil
}

//export OverlayWg_Status
func OverlayWg_Status() *C.char {
	return C.CString(overlaywg.Status())
}

//export OverlayWg_Dial
func OverlayWg_Dial(host *C.char, port C.int, handle *C.longlong) *C.char {
	id, err := overlaywg.Dial(C.GoString(host), int(port))
	if err != nil {
		return C.CString(err.Error())
	}
	*handle = C.longlong(id)
	return nil
}

//export OverlayWg_Read
func OverlayWg_Read(id C.longlong, buffer unsafe.Pointer, offset C.int, length C.int) C.int {
	data, size, ok := window(buffer, offset, length)
	if !ok {
		return -1
	}
	return C.int(overlaywg.Read(int64(id), data, 0, size))
}

//export OverlayWg_Write
func OverlayWg_Write(id C.longlong, buffer unsafe.Pointer, offset C.int, length C.int) C.int {
	data, size, ok := window(buffer, offset, length)
	if !ok {
		return -1
	}
	return C.int(overlaywg.Write(int64(id), data, 0, size))
}

//export OverlayWg_Close
func OverlayWg_Close(id C.longlong) C.int {
	if err := overlaywg.Close(int64(id)); err != nil {
		return -1
	}
	return 0
}

//export OverlayWg_LastError
func OverlayWg_LastError() *C.char {
	return C.CString(overlaywg.LastError())
}

//export OverlayWg_Free
func OverlayWg_Free(value *C.char) {
	if value == nil {
		return
	}
	C.free(unsafe.Pointer(value))
}

// window validates the (buffer, offset, length) triple and returns the sub-slice it names.
// ok=false covers exactly the cases the Android binding treats as a finished stream: a null or
// out-of-range window, which cannot describe a live read.
func window(buffer unsafe.Pointer, offset C.int, length C.int) ([]byte, int, bool) {
	start, size := int(offset), int(length)
	if buffer == nil || start < 0 || size < 0 {
		return nil, 0, false
	}
	all := unsafe.Slice((*byte)(buffer), start+size)
	return all[start:], size, true
}
