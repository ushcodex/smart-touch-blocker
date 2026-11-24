#![no_std]
#![no_main]

use core::arch::asm;

#[panic_handler]
fn panic(_info: &core::panic::PanicInfo) -> ! {
    exit(1);
}

#[no_mangle]
pub extern "C" fn rust_eh_personality() {}

unsafe fn syscall1(nr: usize, a0: usize) -> ! {
    asm!("svc #0", in("x8") nr, in("x0") a0, options(noreturn));
}

unsafe fn syscall3(nr: usize, a0: usize, a1: usize, a2: usize) -> usize {
    let ret: usize;
    asm!("svc #0", inout("x8") nr => _, inout("x0") a0 => ret, in("x1") a1, in("x2") a2, options(nostack));
    ret
}

unsafe fn syscall4(nr: usize, a0: usize, a1: usize, a2: usize, a3: usize) -> usize {
    let ret: usize;
    asm!("svc #0", inout("x8") nr => _, inout("x0") a0 => ret, in("x1") a1, in("x2") a2, in("x3") a3, options(nostack));
    ret
}

fn exit(code: usize) -> ! {
    unsafe { syscall1(93, code) }
}

fn print(msg: &[u8]) {
    unsafe { syscall3(64, 1, msg.as_ptr() as usize, msg.len()); }
}

#[no_mangle]
pub unsafe extern "C" fn memset(dest: *mut u8, c: i32, n: usize) -> *mut u8 {
    let mut i = 0;
    while i < n {
        *dest.add(i) = c as u8;
        i += 1;
    }
    dest
}

#[no_mangle]
pub unsafe extern "C" fn _start() -> ! {
    print(b"Scanning /dev/input devices:\n");
    let mut path = *b"/dev/input/event00\0";
    for i in 0..16 {
        if i < 10 {
            path[16] = b'0' + i as u8;
            path[17] = 0;
        } else {
            path[16] = b'1';
            path[17] = b'0' + (i - 10) as u8;
            path[18] = 0;
        }
        let fd = syscall4(56, -100isize as usize, path.as_ptr() as usize, 0, 0) as isize;
        if fd >= 0 {
            let mut name_buf = [0u8; 128];
            let res = syscall3(29, fd as usize, 0x80804506, name_buf.as_mut_ptr() as usize) as isize;
            let mut prop_buf = [0u8; 8];
            let _ = syscall3(29, fd as usize, 0x80084509, prop_buf.as_mut_ptr() as usize);
            let is_direct = (prop_buf[0] & (1 << 1)) != 0; // INPUT_PROP_DIRECT
            print(b"  ");
            let p_len = if i < 10 { 17 } else { 18 };
            print(&path[..p_len]);
            print(b" -> \"");
            let len = if res > 0 { res as usize } else { 0 };
            let mut s_len = 0;
            while s_len < len && name_buf[s_len] != 0 { s_len += 1; }
            print(&name_buf[..s_len]);
            print(b"\"");
            if is_direct {
                print(b" [TOUCHSCREEN DIRECT]");
            }
            print(b"\n");
            syscall3(57, fd as usize, 0, 0); // close
        }
    }
    exit(0);
}
