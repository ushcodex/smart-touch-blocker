#![no_std]
#![no_main]

use core::arch::asm;

#[panic_handler]
fn panic(_info: &core::panic::PanicInfo) -> ! {
    loop {}
}

unsafe fn syscall1(nr: usize, a0: usize) -> ! {
    asm!("svc #0", in("x8") nr, in("x0") a0, options(noreturn));
}

unsafe fn syscall5(nr: usize, a0: usize, a1: usize, a2: usize, a3: usize, a4: usize) -> usize {
    let ret: usize;
    asm!("svc #0", inout("x8") nr => _, inout("x0") a0 => ret, in("x1") a1, in("x2") a2, in("x3") a3, in("x4") a4, options(nostack));
    ret
}

#[no_mangle]
pub unsafe extern "C" fn _start() -> ! {
    // clone(SIGCHLD=17, 0, 0, 0, 0)
    let pid = syscall5(220, 17, 0, 0, 0, 0) as isize;
    if pid > 0 {
        // Parent exits immediately with code 0
        syscall1(93, 0);
    }
    // Child: setsid() = 157
    let _ = syscall5(157, 0, 0, 0, 0, 0);

    // Sleep for 30 seconds to verify ppid is 1
    let ts = [30i64, 0i64];
    asm!("svc #0", in("x8") 101, in("x0") ts.as_ptr() as usize, in("x1") 0, in("x2") 0, options(nostack));

    syscall1(93, 0);
}
