#![no_std]
#![no_main]

use core::arch::asm;

#[panic_handler]
fn panic(_info: &core::panic::PanicInfo) -> ! {
    exit(1);
}

unsafe fn syscall3(nr: usize, a0: usize, a1: usize, a2: usize) -> usize {
    let ret: usize;
    asm!(
        "svc #0",
        inout("x8") nr => _,
        inout("x0") a0 => ret,
        in("x1") a1,
        in("x2") a2,
        options(nostack)
    );
    ret
}

unsafe fn syscall1(nr: usize, a0: usize) -> ! {
    asm!(
        "svc #0",
        in("x8") nr,
        in("x0") a0,
        options(noreturn)
    );
}

fn exit(code: usize) -> ! {
    unsafe { syscall1(93, code) }
}

#[no_mangle]
pub unsafe extern "C" fn _start() -> ! {
    let msg = b"Hello from pure AArch64 binary!\n";
    syscall3(64, 1, msg.as_ptr() as usize, msg.len());
    exit(0);
}
