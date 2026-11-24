#![no_std]
#![no_main]

use core::arch::asm;

#[panic_handler]
fn panic(_info: &core::panic::PanicInfo) -> ! {
    exit(1);
}

unsafe fn syscall1(nr: usize, a0: usize) -> ! {
    asm!(
        "svc #0",
        in("x8") nr,
        in("x0") a0,
        options(noreturn)
    );
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

unsafe fn syscall4(nr: usize, a0: usize, a1: usize, a2: usize, a3: usize) -> usize {
    let ret: usize;
    asm!(
        "svc #0",
        inout("x8") nr => _,
        inout("x0") a0 => ret,
        in("x1") a1,
        in("x2") a2,
        in("x3") a3,
        options(nostack)
    );
    ret
}

fn exit(code: usize) -> ! {
    unsafe { syscall1(93, code) }
}

fn print(msg: &[u8]) {
    unsafe { syscall3(64, 1, msg.as_ptr() as usize, msg.len()); }
}

#[repr(C)]
struct TimeSpec {
    tv_sec: i64,
    tv_nsec: i64,
}

#[no_mangle]
pub unsafe extern "C" fn _start() -> ! {
    // openat(AT_FDCWD = -100, path, O_RDWR = 2, 0)
    let path = b"/dev/input/event4\0";
    let fd = syscall4(56, -100isize as usize, path.as_ptr() as usize, 2, 0) as isize;
    if fd < 0 {
        print(b"Failed to open /dev/input/event4 (RDWR), trying RDONLY...\n");
        let fd_ro = syscall4(56, -100isize as usize, path.as_ptr() as usize, 0, 0) as isize;
        if fd_ro < 0 {
            print(b"Error: Cannot open /dev/input/event4!\n");
            exit(1);
        }
        test_grab(fd_ro as usize);
    } else {
        test_grab(fd as usize);
    }
    exit(0);
}

unsafe fn test_grab(fd: usize) {
    // ioctl(fd, EVIOCGRAB = 0x40044590, 1)
    let res = syscall3(29, fd, 0x40044590, 1) as isize;
    if res == 0 {
        print(b"SUCCESS: EVIOCGRAB enabled! Touchscreen is now exclusively grabbed!\n");
        print(b"Mouse touches are unaffected, touchscreen is blocked.\n");
        print(b"Sleeping for 5 seconds for test...\n");
        let ts = TimeSpec { tv_sec: 5, tv_nsec: 0 };
        syscall3(101, &ts as *const _ as usize, 0, 0); // nanosleep
        // release grab
        syscall3(29, fd, 0x40044590, 0);
        print(b"Released grab. Done.\n");
    } else {
        print(b"ioctl EVIOCGRAB failed!\n");
    }
}
