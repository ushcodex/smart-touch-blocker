#![no_std]
#![no_main]

use core::arch::{asm, global_asm};

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

fn exit(code: usize) -> ! {
    unsafe { syscall1(93, code) }
}

fn print(msg: &[u8]) {
    unsafe { syscall3(64, 1, msg.as_ptr() as usize, msg.len()); }
}

global_asm!(
    ".globl _start",
    "_start:",
    "ldr x0, [sp]",
    "add x1, sp, #8",
    "b main"
);

#[no_mangle]
pub unsafe extern "C" fn main(argc: usize, argv: *const *const u8) -> ! {
    print(b"Args count: ");
    let mut buf = [0u8; 4];
    buf[0] = b'0' + (argc as u8);
    buf[1] = b'\n';
    print(&buf[..2]);

    for i in 0..argc {
        let p = *argv.add(i);
        let mut len = 0;
        while *p.add(len) != 0 { len += 1; }
        print(b"  arg: ");
        let slice = core::slice::from_raw_parts(p, len);
        print(slice);
        print(b"\n");
    }
    exit(0);
}
