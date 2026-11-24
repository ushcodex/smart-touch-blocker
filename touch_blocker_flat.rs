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

fn exit(code: usize) -> ! {
    unsafe { syscall1(93, code) }
}

fn print(msg: &[u8]) {
    unsafe { syscall3(64, 1, msg.as_ptr() as usize, msg.len()); }
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

#[repr(C)]
struct TimeSpec {
    tv_sec: i64,
    tv_nsec: i64,
}

fn sleep_ms(ms: usize) {
    let ts = TimeSpec {
        tv_sec: (ms / 1000) as i64,
        tv_nsec: ((ms % 1000) * 1_000_000) as i64,
    };
    unsafe { syscall3(101, &ts as *const _ as usize, 0, 0); }
}

const EVIOCGNAME_128: usize = 0x80804506;
const EVIOCGPROP_8: usize = 0x80084509;
const EVIOCGRAB: usize = 0x40044590;

const O_RDONLY: usize = 0;
const O_RDWR: usize = 2;
const O_NONBLOCK: usize = 0x800;
const O_CREAT: usize = 0x40;
const O_TRUNC: usize = 0x200;

const PID_FILE: &[u8] = b"/data/local/tmp/touch_blocker.pid\0";

#[no_mangle]
pub unsafe extern "C" fn _start() -> ! {
    // 1. Read command from /proc/self/cmdline
    let mut cmd = b"status" as &[u8];
    let cfd = syscall4(56, -100isize as usize, b"/proc/self/cmdline\0".as_ptr() as usize, O_RDONLY, 0) as isize;
    let mut arg_buf = [0u8; 256];
    if cfd >= 0 {
        let n = syscall3(63, cfd as usize, arg_buf.as_mut_ptr() as usize, 255) as isize;
        syscall3(57, cfd as usize, 0, 0); // close
        if n > 0 {
            // find first null byte
            let mut i = 0;
            while i < n as usize && arg_buf[i] != 0 { i += 1; }
            if i + 1 < n as usize {
                let start = i + 1;
                let mut end = start;
                while end < n as usize && arg_buf[end] != 0 { end += 1; }
                let arg = &arg_buf[start..end];
                if bytes_match(arg, b"off") || bytes_match(arg, b"stop") || bytes_match(arg, b"--unblock") {
                    cmd = b"off";
                } else if bytes_match(arg, b"on") || bytes_match(arg, b"start") || bytes_match(arg, b"--block") {
                    cmd = b"on";
                } else if bytes_match(arg, b"run") {
                    cmd = b"run";
                } else if bytes_match(arg, b"daemon") || bytes_match(arg, b"-d") {
                    cmd = b"daemon";
                } else if bytes_match(arg, b"toggle") {
                    cmd = b"toggle";
                } else if bytes_match(arg, b"help") || bytes_match(arg, b"-h") {
                    cmd = b"help";
                }
            }
        }
    }

    if bytes_match(cmd, b"help") {
        print(b"=== Smart Touch Blocker (Selective Input Isolation) ===\n");
        print(b"Usage: touch_blocker [command]\n\n");
        print(b"Commands:\n");
        print(b"  status   Display detected input devices and current blocker state\n");
        print(b"  on       Engage touchscreen blocking in background\n");
        print(b"  off      Disengage touchscreen blocking & restore touchscreen\n");
        print(b"  run      Run in foreground with real-time ghost touch counter\n");
        print(b"  toggle   Toggle blocking state\n");
        exit(0);
    }

    // Read PID file if present
    let mut running_pid = 0usize;
    let pfd = syscall4(56, -100isize as usize, PID_FILE.as_ptr() as usize, O_RDONLY, 0) as isize;
    if pfd >= 0 {
        let mut pbuf = [0u8; 32];
        let pn = syscall3(63, pfd as usize, pbuf.as_mut_ptr() as usize, 31) as isize;
        syscall3(57, pfd as usize, 0, 0);
        if pn > 0 {
            for j in 0..pn as usize {
                let c = pbuf[j];
                if c >= b'0' && c <= b'9' {
                    running_pid = running_pid * 10 + (c - b'0') as usize;
                } else if running_pid > 0 {
                    break;
                }
            }
        }
    }

    if bytes_match(cmd, b"off") {
        if running_pid > 0 {
            print(b"[*] Stopping Touch Blocker daemon (PID: ");
            print_num(running_pid);
            print(b")...\n");
            // send SIGTERM
            let _ = syscall2(129, running_pid, 15);
            let _ = syscall3(35, -100isize as usize, PID_FILE.as_ptr() as usize, 0); // unlink
            print(b"[OK] Touchscreen has been restored.\n");
        } else {
            print(b"[*] Touch Blocker is not currently active.\n");
        }
        exit(0);
    }

    if bytes_match(cmd, b"toggle") {
        if running_pid > 0 {
            let _ = syscall2(129, running_pid, 15);
            let _ = syscall3(35, -100isize as usize, PID_FILE.as_ptr() as usize, 0);
            print(b"[OK] Toggled: Touchscreen is now RESTORED.\n");
            exit(0);
        } else {
            cmd = b"on";
        }
    }

    if bytes_match(cmd, b"status") {
        print(b"==========================================================\n");
        print(b"  SMART TOUCH BLOCKER - HARDWARE INPUT STATUS\n");
        print(b"==========================================================\n");
        let mut path = *b"/dev/input/event00\0";
        let mut has_ts = false;
        let mut has_mouse = false;

        for i in 0..16 {
            if i < 10 {
                path[16] = b'0' + i as u8;
                path[17] = 0;
            } else {
                path[16] = b'1';
                path[17] = b'0' + (i - 10) as u8;
                path[18] = 0;
            }
            let fd = syscall4(56, -100isize as usize, path.as_ptr() as usize, O_RDONLY, 0) as isize;
            if fd >= 0 {
                let mut name_buf = [0u8; 128];
                let res = syscall3(29, fd as usize, EVIOCGNAME_128, name_buf.as_mut_ptr() as usize) as isize;
                let mut prop_buf = [0u8; 8];
                let _ = syscall3(29, fd as usize, EVIOCGPROP_8, prop_buf.as_mut_ptr() as usize);
                let is_direct = (prop_buf[0] & (1 << 1)) != 0;

                let mut nlen = 0;
                if res > 0 {
                    while nlen < (res as usize) && name_buf[nlen] != 0 { nlen += 1; }
                }

                let is_mouse = sub_str(&name_buf[..nlen], b"Mouse")
                    || sub_str(&name_buf[..nlen], b"mouse")
                    || sub_str(&name_buf[..nlen], b"pointer");

                print(b"  ");
                let plen = if i < 10 { 17 } else { 18 };
                print(&path[..plen]);
                print(b" -> \"");
                print(&name_buf[..nlen]);
                print(b"\"");

                if is_direct || sub_str(&name_buf[..nlen], b"ts") {
                    print(b"  [TOUCHSCREEN] (Blocked when active)");
                    has_ts = true;
                } else if is_mouse {
                    print(b"  [MOUSE] (Whitelisted - Always active)");
                    has_mouse = true;
                }
                print(b"\n");
                syscall3(57, fd as usize, 0, 0); // close
            }
        }
        print(b"----------------------------------------------------------\n");
        print(b"  Touchscreen: ");
        if has_ts { print(b"DETECTED\n"); } else { print(b"NOT DETECTED\n"); }
        print(b"  Mouse:       ");
        if has_mouse { print(b"CONNECTED & WHITELISTED\n"); } else { print(b"NO MOUSE CONNECTED\n"); }
        print(b"  State:       ");
        if running_pid > 0 {
            print(b"ACTIVE [PID: ");
            print_num(running_pid);
            print(b"] - Touchscreen is BLOCKED, Mouse is ALLOWED\n");
        } else {
            print(b"INACTIVE - Touchscreen is unblocked\n");
        }
        print(b"==========================================================\n");
        exit(0);
    }

    // Commands: on, run, daemon
    if running_pid > 0 {
        print(b"[*] Blocker is already ACTIVE (PID: ");
        print_num(running_pid);
        print(b").\n");
        print(b"    To turn off, run: touch_blocker off\n");
        exit(0);
    }

    // Find touchscreen fds
    let mut ts_fds = [0usize; 4];
    let mut ts_count = 0;
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
        let fd = syscall4(56, -100isize as usize, path.as_ptr() as usize, O_RDONLY | O_NONBLOCK, 0) as isize;
        if fd >= 0 {
            let mut name_buf = [0u8; 128];
            let res = syscall3(29, fd as usize, EVIOCGNAME_128, name_buf.as_mut_ptr() as usize) as isize;
            let mut prop_buf = [0u8; 8];
            let _ = syscall3(29, fd as usize, EVIOCGPROP_8, prop_buf.as_mut_ptr() as usize);
            let is_direct = (prop_buf[0] & (1 << 1)) != 0;

            let mut nlen = 0;
            if res > 0 {
                while nlen < (res as usize) && name_buf[nlen] != 0 { nlen += 1; }
            }

            if (is_direct || sub_str(&name_buf[..nlen], b"ts")) && ts_count < 4 {
                // Apply EVIOCGRAB
                let grab_res = syscall3(29, fd as usize, EVIOCGRAB, 1) as isize;
                if grab_res == 0 {
                    print(b"[+] Exclusively GRABBED ");
                    let plen = if i < 10 { 17 } else { 18 };
                    print(&path[..plen]);
                    print(b" (\"");
                    print(&name_buf[..nlen]);
                    print(b"\")\n");
                    ts_fds[ts_count] = fd as usize;
                    ts_count += 1;
                } else {
                    syscall3(57, fd as usize, 0, 0);
                }
            } else {
                syscall3(57, fd as usize, 0, 0);
            }
        }
    }

    if ts_count == 0 {
        print(b"[!] Error: Could not grab touchscreen device.\n");
        exit(1);
    }

    // Write PID file
    let my_pid = getpid();
    let wpfd = syscall4(56, -100isize as usize, PID_FILE.as_ptr() as usize, O_RDWR | O_CREAT | O_TRUNC, 0o666) as isize;
    if wpfd >= 0 {
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
        syscall3(64, wpfd as usize, pbuf.as_ptr().add(idx) as usize, len);
        let nl = b"\n";
        syscall3(64, wpfd as usize, nl.as_ptr() as usize, 1);
        syscall3(57, wpfd as usize, 0, 0);
    }

    print(b"----------------------------------------------------------\n");
    print(b"[OK] Touchscreen digitizer is 100% BLOCKED.\n");
    print(b"[OK] Mouse input is 100% ALLOWED and fully functional.\n");
    print(b"[*] Discarding all phantom touchscreen events...\n");
    print(b"----------------------------------------------------------\n");

    let mut ghost_count = 0usize;
    let mut raw_buf = [0u8; 512];
    let is_fg = bytes_match(cmd, b"run");
    let mut loop_count = 0usize;

    loop {
        // Read from grabbed fds to drain phantom touches
        for j in 0..ts_count {
            let fd = ts_fds[j];
            let nr = syscall3(63, fd, raw_buf.as_mut_ptr() as usize, 512) as isize;
            if nr > 0 {
                ghost_count += (nr as usize) / 24; // sizeof(struct input_event) on 64-bit = 24
            }
        }

        sleep_ms(100);
        loop_count += 1;

        if is_fg && loop_count % 30 == 0 {
            print(b"[*] Ghost touches blocked: ");
            print_num(ghost_count);
            print(b"\n");
        }

        // Check if PID file still exists
        let chk = syscall4(56, -100isize as usize, PID_FILE.as_ptr() as usize, O_RDONLY, 0) as isize;
        if chk < 0 {
            print(b"[*] Stop signal received. Unblocking...\n");
            break;
        }
        syscall3(57, chk as usize, 0, 0);
    }

    // Release grab and restore touchscreen
    for j in 0..ts_count {
        let fd = ts_fds[j];
        syscall3(29, fd, EVIOCGRAB, 0);
        syscall3(57, fd, 0, 0);
    }
    let _ = syscall3(35, -100isize as usize, PID_FILE.as_ptr() as usize, 0);

    print(b"[OK] Touchscreen digitizer unblocked and restored.\n");
    exit(0);
}

fn bytes_match(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut i = 0;
    while i < a.len() {
        if a[i] != b[i] {
            return false;
        }
        i += 1;
    }
    true
}

fn sub_str(haystack: &[u8], needle: &[u8]) -> bool {
    if needle.len() > haystack.len() || needle.len() == 0 {
        return false;
    }
    let max = haystack.len() - needle.len();
    let mut i = 0;
    while i <= max {
        let mut match_ = true;
        let mut j = 0;
        while j < needle.len() {
            if haystack[i + j] != needle[j] {
                match_ = false;
                break;
            }
            j += 1;
        }
        if match_ {
            return true;
        }
        i += 1;
    }
    false
}
