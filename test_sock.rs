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

unsafe fn syscall3(nr: usize, a0: usize, a1: usize, a2: usize) -> usize {
    let ret: usize;
    asm!("svc #0", inout("x8") nr => _, inout("x0") a0 => ret, in("x1") a1, in("x2") a2, options(nostack));
    ret
}

#[repr(C)]
struct SockAddrIn {
    sin_family: u16,
    sin_port: u16,
    sin_addr: u32,
    sin_zero: [u8; 8],
}

#[no_mangle]
pub unsafe extern "C" fn _start() -> ! {
    // socket(AF_INET=2, SOCK_STREAM=1, 0) -> syscall 198
    let sfd = syscall3(198, 2, 1, 0) as isize;
    if sfd < 0 {
        syscall1(93, 1);
    }

    // bind to 127.0.0.1:9876
    let port_be = (9876u16).to_be();
    let addr_be = 0x7F000001u32.to_be(); // 127.0.0.1

    let addr = SockAddrIn {
        sin_family: 2,
        sin_port: port_be,
        sin_addr: addr_be,
        sin_zero: [0; 8],
    };

    let bres = syscall3(200, sfd as usize, &addr as *const _ as usize, 16) as isize;
    if bres < 0 {
        syscall1(93, 2);
    }

    // listen(sfd, 5) -> syscall 201
    let lres = syscall3(201, sfd as usize, 5, 0) as isize;
    if lres < 0 {
        syscall1(93, 3);
    }

    // Success! Exit with 0
    syscall1(93, 0);
}
