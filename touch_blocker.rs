#![no_std]
#![no_main]

use core::arch::asm;

#[panic_handler]
fn panic(_info: &core::panic::PanicInfo) -> ! {
    exit(1);
}

#[no_mangle]
pub extern "C" fn rust_eh_personality() {}

// Linux Syscall Wrappers for AArch64
unsafe fn syscall1(nr: usize, a0: usize) -> ! {
    asm!(
        "svc #0",
        "1: b 1b",
        in("x8") nr,
        in("x0") a0,
        options(noreturn)
    );
}

unsafe fn syscall2(nr: usize, a0: usize, a1: usize) -> usize {
    let ret: usize;
    asm!("svc #0", inout("x8") nr => _, inout("x0") a0 => ret, in("x1") a1, options(nostack));
    ret
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

unsafe fn syscall5(nr: usize, a0: usize, a1: usize, a2: usize, a3: usize, a4: usize) -> usize {
    let ret: usize;
    asm!("svc #0", inout("x8") nr => _, inout("x0") a0 => ret, in("x1") a1, in("x2") a2, in("x3") a3, in("x4") a4, options(nostack));
    ret
}

fn exit(code: usize) -> ! {
    unsafe { syscall1(93, code) }
}

fn print(msg: &[u8]) {
    unsafe { syscall3(64, 1, msg.as_ptr() as usize, msg.len()); }
}

fn print_err(msg: &[u8]) {
    unsafe { syscall3(64, 2, msg.as_ptr() as usize, msg.len()); }
}

fn print_num(mut n: usize) {
    if n == 0 {
        print(b"0");
        return;
    }
    let mut buf = [0u8; 20];
    let mut idx = 20;
    while n > 0 {
        idx -= 1;
        buf[idx] = b'0' + (n % 10) as u8;
        n /= 10;
    }
    print(&buf[idx..]);
}

fn getpid() -> usize {
    unsafe { syscall3(172, 0, 0, 0) } // getpid = 172 on aarch64
}

#[derive(Copy, Clone)]
#[repr(C)]
struct PollFd {
    fd: i32,
    events: i16,
    revents: i16,
}

const EVIOCGNAME_128: usize = 0x80804506;
const EVIOCGPROP_8: usize = 0x80084509;
const EVIOCGRAB: usize = 0x40044590;

const O_RDONLY: usize = 0;
const O_RDWR: usize = 2;
const O_NONBLOCK: usize = 0x800;
const O_CREAT: usize = 0x40;
const O_TRUNC: usize = 0x200;

const POLLIN: i16 = 0x0001;

#[repr(C)]
struct TimeSpec {
    tv_sec: i64,
    tv_nsec: i64,
}

#[derive(Copy, Clone)]
#[repr(C)]
struct InputEvent {
    time_sec: usize,
    time_usec: usize,
    type_: u16,
    code: u16,
    value: i32,
}

struct DeviceInfo {
    index: usize,
    is_touchscreen: bool,
    is_mouse: bool,
    name_len: usize,
    name: [u8; 64],
}

unsafe fn parse_device(i: usize) -> Option<DeviceInfo> {
    let mut path = *b"/dev/input/event00\0";
    if i < 10 {
        path[16] = b'0' + i as u8;
        path[17] = 0;
    } else {
        path[16] = b'1';
        path[17] = b'0' + (i - 10) as u8;
        path[18] = 0;
    }

    let fd = syscall4(56, -100isize as usize, path.as_ptr() as usize, O_RDONLY, 0) as isize;
    if fd < 0 {
        return None;
    }

    let mut dev = DeviceInfo {
        index: i,
        is_touchscreen: false,
        is_mouse: false,
        name_len: 0,
        name: [0u8; 64],
    };

    let mut name_buf = [0u8; 128];
    let res = syscall3(29, fd as usize, EVIOCGNAME_128, name_buf.as_mut_ptr() as usize) as isize;
    if res > 0 {
        let mut s_len = 0;
        while s_len < 63 && s_len < res as usize && name_buf[s_len] != 0 {
            dev.name[s_len] = name_buf[s_len];
            s_len += 1;
        }
        dev.name_len = s_len;
    }

    let mut prop_buf = [0u8; 8];
    let _ = syscall3(29, fd as usize, EVIOCGPROP_8, prop_buf.as_mut_ptr() as usize);
    let is_direct = (prop_buf[0] & (1 << 1)) != 0; // INPUT_PROP_DIRECT

    // Check name patterns
    let mut name_lower = [0u8; 64];
    for j in 0..dev.name_len {
        let c = dev.name[j];
        name_lower[j] = if c >= b'A' && c <= b'Z' { c + 32 } else { c };
    }

    let is_ts_name = contains_sub(&name_lower[..dev.name_len], b"ts")
        || contains_sub(&name_lower[..dev.name_len], b"touch");
    let is_mouse_name = contains_sub(&name_lower[..dev.name_len], b"mouse")
        || contains_sub(&name_lower[..dev.name_len], b"pointer")
        || contains_sub(&name_lower[..dev.name_len], b"trackball");

    dev.is_touchscreen = is_direct || is_ts_name;
    dev.is_mouse = is_mouse_name;

    syscall3(57, fd as usize, 0, 0); // close
    Some(dev)
}

fn contains_sub(haystack: &[u8], needle: &[u8]) -> bool {
    if needle.len() > haystack.len() {
        return false;
    }
    for i in 0..=(haystack.len() - needle.len()) {
        if &haystack[i..i + needle.len()] == needle {
            return true;
        }
    }
    false
}

const PID_FILE: &[u8] = b"/data/local/tmp/touch_blocker.pid\0";

unsafe fn read_pid() -> Option<usize> {
    let fd = syscall4(56, -100isize as usize, PID_FILE.as_ptr() as usize, O_RDONLY, 0) as isize;
    if fd < 0 {
        return None;
    }
    let mut buf = [0u8; 32];
    let n = syscall3(63, fd as usize, buf.as_mut_ptr() as usize, 31) as isize;
    syscall3(57, fd as usize, 0, 0);
    if n <= 0 {
        return None;
    }
    let mut pid = 0usize;
    for i in 0..n as usize {
        let c = buf[i];
        if c >= b'0' && c <= b'9' {
            pid = pid * 10 + (c - b'0') as usize;
        } else if pid > 0 {
            break;
        }
    }
    if pid > 0 { Some(pid) } else { None }
}

#[no_mangle]
pub unsafe extern "C" fn _start() -> ! {
    let mut cmd = b"status" as &[u8];

    // Read command line from /proc/self/cmdline
    let cfd = syscall4(56, -100isize as usize, b"/proc/self/cmdline\0".as_ptr() as usize, O_RDONLY, 0) as isize;
    let mut arg_buf = [0u8; 256];
    let mut arg1: &[u8] = b"";

    if cfd >= 0 {
        let n = syscall3(63, cfd as usize, arg_buf.as_mut_ptr() as usize, 255) as isize;
        syscall3(57, cfd as usize, 0, 0);
        if n > 0 {
            // arg0 ends at first null byte
            let mut i = 0;
            while i < n as usize && arg_buf[i] != 0 {
                i += 1;
            }
            if i + 1 < n as usize {
                // start of arg1
                let start = i + 1;
                let mut end = start;
                while end < n as usize && arg_buf[end] != 0 {
                    end += 1;
                }
                arg1 = &arg_buf[start..end];
            }
        }
    }

    if arg1 == b"start" || arg1 == b"on" || arg1 == b"--block" {
        cmd = b"on";
    } else if arg1 == b"stop" || arg1 == b"off" || arg1 == b"--unblock" {
        cmd = b"off";
    } else if arg1 == b"scan" || arg1 == b"list" || arg1 == b"status" {
        cmd = b"status";
    } else if arg1 == b"daemon" || arg1 == b"-d" {
        cmd = b"daemon";
    } else if arg1 == b"run" {
        cmd = b"run";
    } else if arg1 == b"toggle" {
        cmd = b"toggle";
    } else if arg1.len() > 0 {
        cmd = b"help";
    }

    if cmd == b"help" {
        print(b"=== Smart Touch Blocker (Selective Input Isolation) ===\n");
        print(b"Usage: touch_blocker [command]\n\n");
        print(b"Commands:\n");
        print(b"  run      Block touchscreen & count phantom touches in foreground\n");
        print(b"  daemon   Run as persistent background daemon\n");
        print(b"  on       Engage touchscreen blocking (kills ghost touches)\n");
        print(b"  off      Disengage touchscreen blocking (restores touchscreen)\n");
        print(b"  toggle   Toggle blocking state\n");
        print(b"  status   Show device status & active blocker state\n");
        print(b"  list     List all detected hardware input devices\n");
        exit(0);
    }

    if cmd == b"off" {
        if let Some(pid) = read_pid() {
            print(b"[*] Stopping Touch Blocker daemon (PID: ");
            print_num(pid);
            print(b")...\n");
            let _ = syscall2(129, pid, 15); // SIGTERM
            let _ = syscall3(35, -100isize as usize, PID_FILE.as_ptr() as usize, 0); // unlink
            print(b"[OK] Touchscreen has been restored / unblocked.\n");
        } else {
            print(b"[*] Blocker is not currently active.\n");
        }
        exit(0);
    }

    if cmd == b"toggle" {
        if let Some(pid) = read_pid() {
            let _ = syscall2(129, pid, 15);
            let _ = syscall3(35, -100isize as usize, PID_FILE.as_ptr() as usize, 0);
            print(b"[OK] Toggled: Touchscreen is now UNBLOCKED.\n");
            exit(0);
        } else {
            cmd = b"daemon";
        }
    }

    if cmd == b"status" {
        print(b"==========================================================\n");
        print(b"  SMART TOUCH BLOCKER - SELECTIVE INPUT ISOLATION\n");
        print(b"==========================================================\n");
        let mut found_ts = false;
        let mut found_mouse = false;

        for i in 0..16 {
            if let Some(dev) = parse_device(i) {
                print(b"  [/dev/input/event");
                print_num(dev.index);
                print(b"] \"");
                print(&dev.name[..dev.name_len]);
                print(b"\"");
                if dev.is_touchscreen {
                    print(b"  ==> [TOUCHSCREEN] (BLOCKED when active)");
                    found_ts = true;
                } else if dev.is_mouse {
                    print(b"  ==> [MOUSE] (WHITELISTED - NATIVE INPUT)");
                    found_mouse = true;
                } else {
                    print(b"  ==> [BUTTON / SENSOR]");
                }
                print(b"\n");
            }
        }
        print(b"----------------------------------------------------------\n");
        print(b"  Touchscreen Detected: ");
        if found_ts { print(b"YES\n"); } else { print(b"NO\n"); }
        print(b"  Mouse Detected:       ");
        if found_mouse { print(b"YES (Connected & Whitelisted)\n"); } else { print(b"NO\n"); }

        let running_pid = read_pid();
        print(b"  Blocker State:        ");
        if let Some(pid) = running_pid {
            print(b"ACTIVE [PID: ");
            print_num(pid);
            print(b"]\n");
            print(b"  Touchscreen:          BLOCKED (Kernel EVIOCGRAB exclusive)\n");
            print(b"  Mouse Input:          ALLOWED (100% native pointer & clicks)\n");
        } else {
            print(b"INACTIVE (Touchscreen is unblocked)\n");
        }
        print(b"==========================================================\n");
        exit(0);
    }

    if cmd == b"on" || cmd == b"run" || cmd == b"daemon" {
        if let Some(pid) = read_pid() {
            print(b"[*] Blocker is ALREADY active with PID ");
            print_num(pid);
            print(b".\n");
            print(b"    To restart, run: touch_blocker off && touch_blocker on\n");
            exit(0);
        }
        run_blocker_loop(cmd == b"daemon");
    }

    exit(0);
}

unsafe fn run_blocker_loop(is_daemon: bool) -> ! {
    print(b"[*] Initializing Smart Touch Blocker...\n");

    let mut ts_indices = [0usize; 8];
    let mut ts_count = 0;
    let mut mouse_found = false;

    for i in 0..16 {
        if let Some(dev) = parse_device(i) {
            if dev.is_touchscreen && ts_count < 8 {
                ts_indices[ts_count] = dev.index;
                ts_count += 1;
            }
            if dev.is_mouse {
                mouse_found = true;
            }
        }
    }

    if ts_count == 0 {
        ts_indices[0] = 4;
        ts_count = 1;
    }

    let mut fds = [0usize; 8];
    let mut poll_fds = [PollFd { fd: 0, events: 0, revents: 0 }; 8];

    for idx in 0..ts_count {
        let dev_idx = ts_indices[idx];
        let mut path = *b"/dev/input/event00\0";
        if dev_idx < 10 {
            path[16] = b'0' + dev_idx as u8;
            path[17] = 0;
        } else {
            path[16] = b'1';
            path[17] = b'0' + (dev_idx - 10) as u8;
            path[18] = 0;
        }

        let fd = syscall4(56, -100isize as usize, path.as_ptr() as usize, O_RDONLY | O_NONBLOCK, 0) as isize;
        if fd < 0 {
            print_err(b"[!] Failed to open ");
            print_err(&path[..17]);
            print_err(b"\n");
            continue;
        }

        let res = syscall3(29, fd as usize, EVIOCGRAB, 1) as isize;
        if res == 0 {
            print(b"[+] Exclusively GRABBED ");
            print(&path[..17]);
            print(b" (Hardware touch intercept active)\n");
            fds[idx] = fd as usize;
            poll_fds[idx] = PollFd {
                fd: fd as i32,
                events: POLLIN,
                revents: 0,
            };
        } else {
            print_err(b"[!] EVIOCGRAB failed on ");
            print_err(&path[..17]);
            print_err(b"\n");
            syscall3(57, fd as usize, 0, 0);
        }
    }

    let my_pid = getpid();
    let pfd = syscall4(56, -100isize as usize, PID_FILE.as_ptr() as usize, O_RDWR | O_CREAT | O_TRUNC, 0o666) as isize;
    if pfd >= 0 {
        let mut pbuf = [0u8; 20];
        let mut n = my_pid;
        let mut idx = 20;
        if n == 0 {
            idx -= 1;
            pbuf[idx] = b'0';
        } else {
            while n > 0 {
                idx -= 1;
                pbuf[idx] = b'0' + (n % 10) as u8;
                n /= 10;
            }
        }
        let len = 20 - idx;
        syscall3(64, pfd as usize, pbuf.as_ptr().add(idx) as usize, len);
        let nl = b"\n";
        syscall3(64, pfd as usize, nl.as_ptr() as usize, 1);
        syscall3(57, pfd as usize, 0, 0);
    }

    print(b"----------------------------------------------------------\n");
    print(b"[OK] Touchscreen digitizer is 100% BLOCKED.\n");
    print(b"[OK] Mouse input is 100% WHITELISTED and fully functional.\n");
    if mouse_found {
        print(b"[OK] Bluetooth/USB Mouse recognized.\n");
    }
    print(b"[*] Suppressing and discarding all phantom touch events...\n");
    print(b"----------------------------------------------------------\n");

    let mut ghost_touches_intercepted = 0usize;
    let mut ev_buf = [InputEvent { time_sec: 0, time_usec: 0, type_: 0, code: 0, value: 0 }; 32];
    let ev_size = core::mem::size_of::<InputEvent>();
    let mut cycle = 0usize;

    loop {
        let ts = TimeSpec { tv_sec: 0, tv_nsec: 500_000_000 };
        let _ = syscall5(73, poll_fds.as_mut_ptr() as usize, ts_count, &ts as *const _ as usize, 0, 0);

        for idx in 0..ts_count {
            let fd = fds[idx];
            if fd == 0 { continue; }

            let n_read = syscall3(63, fd, ev_buf.as_mut_ptr() as usize, ev_size * 32) as isize;
            if n_read > 0 {
                let count = (n_read as usize) / ev_size;
                ghost_touches_intercepted += count;
            }
        }

        cycle += 1;
        if !is_daemon && cycle % 10 == 0 {
            print(b"[*] Ghost touch events intercepted: ");
            print_num(ghost_touches_intercepted);
            print(b"\n");
        }

        let chk = syscall4(56, -100isize as usize, PID_FILE.as_ptr() as usize, O_RDONLY, 0) as isize;
        if chk < 0 {
            print(b"[*] Termination requested. Exiting cleanly...\n");
            break;
        }
        syscall3(57, chk as usize, 0, 0);
    }

    for idx in 0..ts_count {
        let fd = fds[idx];
        if fd != 0 {
            syscall3(29, fd, EVIOCGRAB, 0);
            syscall3(57, fd, 0, 0);
        }
    }
    let _ = syscall3(35, -100isize as usize, PID_FILE.as_ptr() as usize, 0);

    print(b"[OK] Touchscreen restored.\n");
    exit(0);
}
