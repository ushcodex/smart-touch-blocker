#![no_std]
#![no_main]

use core::arch::asm;

#[panic_handler]
fn panic(_info: &core::panic::PanicInfo) -> ! {
    exit(1);
}

#[no_mangle]
pub extern "C" fn rust_eh_personality() {}

#[no_mangle]
pub unsafe extern "C" fn memset(dest: *mut u8, c: i32, n: usize) -> *mut u8 {
    let mut i = 0;
    while i < n {
        *dest.add(i) = c as u8;
        i += 1;
    }
    dest
}

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
pub unsafe extern "C" fn _start() -> ! {
    let fd = syscall4(56, -100isize as usize, b"/proc/self/cmdline\0".as_ptr() as usize, 0, 0) as isize;
    if fd >= 0 {
        let mut buf = [0u8; 256];
        let n = syscall3(63, fd as usize, buf.as_mut_ptr() as usize, 255) as isize;
        syscall3(57, fd as usize, 0, 0);
        if n > 0 {
            print(b"Cmdline read: ");
            let mut start = 0;
            for i in 0..n as usize {
                if buf[i] == 0 {
                    print(b"[");
                    print(&buf[start..i]);
                    print(b"] ");
                    start = i + 1;
                }
            }
            print(b"\n");
        }
    }
    exit(0);
}
