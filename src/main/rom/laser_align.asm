; 激光对准控制程序（YouranMod 自带的正版镜像，8 KB，插在个人电脑主板的 BIOS 插座上，映射在 F000:E000–F000:FFFF）
;
; 光刻机对准台上的这块个人电脑主板不跑 DOS：上电从 F000:FFF0 跳到这里，一直跑伺服环。
; 硬件（对准台里的接口板，I/O 口）：
;   300h–30Eh  两个四象限光电二极管的模数转换（左标记 A B C D、右标记 A B C D，每个一个字，12 位无符号）
;   310h       写 1 启动一次转换；读 bit0 = 转换完
;   320h/322h/324h  压电微动台 X、Y、θ 的数模转换（有符号 16 位）
;   330h       给光刻机主控的状态：bit0 锁定，bit1 偏出捕获范围（失锁），bit2 激光太弱
;   340h       氦氖激光功率监测（12 位）
; 8253 定时器 0 设成 1 kHz，伺服环每 1 ms 走一次：读两个标记 → 算 X、Y、θ 误差 → PI 调节 → 写微动台。
; 四象限的和低于门限说明光斑不在标记上（偏出捕获范围），这时停调、报失锁，等人用旋钮粗对准后重新按“激光对准”。
;
; 生成：nasm -f bin -o laser_align.bin laser_align.asm，再由 laser_align_sum.py 补最后一字节的校验和（全部字节相加为 0）。

        cpu     8086
        bits    16
        org     0xE000

ADC_BASE        equ     0x300
ADC_CTRL        equ     0x310
DAC_X           equ     0x320
DAC_Y           equ     0x322
DAC_T           equ     0x324
STATUS          equ     0x330
LASER_MON       equ     0x340
PIT_CH0         equ     0x40
PIT_CTRL        equ     0x43

THRESH          equ     600             ; 一个标记四象限之和的门限（光斑落在标记上）
LASER_MIN       equ     160             ; 激光功率监测的下限
KP              equ     6               ; 比例增益（右移 3 位后）
KI_SHIFT        equ     5               ; 积分增益 = 1/32
LOCK_ERR        equ     8               ; 误差小于这么多（归一化单位）算对上
LOCK_COUNT      equ     200             ; 连续 200 ms 对上才报锁定
DAC_LIMIT       equ     30000

; 变量放在 0000:0500 起的内存里
V_SAMPLES       equ     0x0500          ; 8 个字：左 A B C D、右 A B C D
V_ERR           equ     0x0510          ; X、Y、θ 误差
V_INT           equ     0x0516          ; X、Y、θ 积分
V_OUT           equ     0x051C          ; X、Y、θ 输出
V_LOCK          equ     0x0522          ; 连续对上的次数
V_STAT          equ     0x0524

rom_start:
        db      'YR-LAC 1.00  LASER ALIGNMENT CONTROLLER', 0
        db      'FOR YR LITHOGRAPHY ALIGNER, HE-NE 633 NM', 0
        db      '(C) 2026 YOURAN SERVER', 0

; ---- 上电 ----
cold_start:
        cli
        cld
        xor     ax, ax
        mov     ss, ax
        mov     sp, 0x0400
        mov     ds, ax
        mov     es, ax
        ; 变量清零
        mov     di, V_SAMPLES
        mov     cx, 0x30
        rep     stosw
        ; 微动台回中
        mov     dx, DAC_X
        out     dx, ax
        mov     dx, DAC_Y
        out     dx, ax
        mov     dx, DAC_T
        out     dx, ax
        ; 8253 定时器 0：方式 2，1193182 / 1193 ≈ 1000 Hz
        mov     al, 0x34
        out     PIT_CTRL, al
        mov     ax, 1193
        out     PIT_CH0, al
        mov     al, ah
        out     PIT_CH0, al

; ---- 伺服环 ----
servo:
        call    wait_tick
        call    read_marks
        call    check_signal
        jc      lost
        call    errors
        mov     si, V_ERR
        mov     di, V_INT
        mov     bx, V_OUT
        mov     cx, 3
.axis:  call    pi_axis
        add     si, 2
        add     di, 2
        add     bx, 2
        loop    .axis
        call    write_stage
        call    lock_detect
        jmp     servo

lost:   ; 光斑不在标记上：停调、清积分、报失锁
        mov     word [V_LOCK], 0
        mov     di, V_INT
        xor     ax, ax
        mov     cx, 3
        rep     stosw
        mov     dx, STATUS
        mov     al, [V_STAT]
        out     dx, al
        jmp     servo

; ---- 等 1 ms：读定时器 0 的计数，等它回绕 ----
wait_tick:
        push    bx
        call    pit_count
        mov     bx, ax
.w:     call    pit_count
        cmp     ax, bx
        mov     bx, ax
        jbe     .w                      ; 方式 2 往下数，回绕时变大
        pop     bx
        ret

pit_count:
        xor     al, al
        out     PIT_CTRL, al            ; 锁存计数
        in      al, PIT_CH0
        mov     ah, al
        in      al, PIT_CH0
        xchg    al, ah
        ret

; ---- 读两个标记的四象限 ----
read_marks:
        mov     dx, ADC_CTRL
        mov     al, 1
        out     dx, al
.busy:  in      al, dx
        test    al, 1
        jz      .busy
        mov     dx, ADC_BASE
        mov     di, V_SAMPLES
        mov     cx, 8
.rd:    in      ax, dx
        and     ax, 0x0FFF
        stosw
        add     dx, 2
        loop    .rd
        ret

; ---- 光够不够：每个标记四象限之和过门限、激光功率过下限；不够时置 CF 并在 V_STAT 写原因 ----
check_signal:
        mov     byte [V_STAT], 0
        mov     dx, LASER_MON
        in      ax, dx
        and     ax, 0x0FFF
        cmp     ax, LASER_MIN
        jae     .power_ok
        mov     byte [V_STAT], 4
        stc
        ret
.power_ok:
        mov     si, V_SAMPLES
        call    quad_sum
        cmp     ax, THRESH
        jb      .out
        mov     si, V_SAMPLES + 8
        call    quad_sum
        cmp     ax, THRESH
        jb      .out
        clc
        ret
.out:   mov     byte [V_STAT], 2
        stc
        ret

quad_sum:
        mov     ax, [si]
        add     ax, [si + 2]
        add     ax, [si + 4]
        add     ax, [si + 6]
        ret

; ---- 误差：每个标记 ex = (A+D−B−C)·1024/和，ey = (A+B−C−D)·1024/和；X = (exL+exR)/2，Y = (eyL+eyR)/2，θ = (eyR−eyL)/2 ----
errors:
        mov     si, V_SAMPLES
        call    mark_err                ; 返回 BX = ex，BP = ey
        push    bx
        push    bp
        mov     si, V_SAMPLES + 8
        call    mark_err
        pop     dx                      ; eyL
        pop     ax                      ; exL
        add     ax, bx
        sar     ax, 1
        mov     [V_ERR], ax             ; X
        mov     ax, dx
        add     ax, bp
        sar     ax, 1
        mov     [V_ERR + 2], ax         ; Y
        mov     ax, bp
        sub     ax, dx
        sar     ax, 1
        mov     [V_ERR + 4], ax         ; θ
        ret

mark_err:
        call    quad_sum
        mov     cx, ax                  ; 和
        mov     ax, [si]
        add     ax, [si + 6]
        sub     ax, [si + 2]
        sub     ax, [si + 4]            ; A+D−B−C
        call    normalize
        mov     bx, ax
        mov     ax, [si]
        add     ax, [si + 2]
        sub     ax, [si + 4]
        sub     ax, [si + 6]            ; A+B−C−D
        call    normalize
        mov     bp, ax
        ret

normalize:                              ; AX = AX·1024 / CX（有符号）
        mov     dx, 1024
        imul    dx
        idiv    cx
        ret

; ---- 一个轴的 PI：SI 误差，DI 积分，BX 输出 ----
pi_axis:
        push    cx
        mov     ax, [si]
        mov     cl, KI_SHIFT
        sar     ax, cl
        add     ax, [di]
        call    clamp
        mov     [di], ax
        mov     ax, [si]
        mov     dx, KP
        imul    dx
        mov     cl, 3
        sar     ax, cl
        add     ax, [di]
        neg     ax                      ; 往误差的反方向推
        add     ax, [bx]
        call    clamp
        mov     [bx], ax
        pop     cx
        ret

clamp:
        cmp     ax, DAC_LIMIT
        jle     .lo
        mov     ax, DAC_LIMIT
        ret
.lo:    cmp     ax, -DAC_LIMIT
        jge     .ok
        mov     ax, -DAC_LIMIT
.ok:    ret

write_stage:
        mov     ax, [V_OUT]
        mov     dx, DAC_X
        out     dx, ax
        mov     ax, [V_OUT + 2]
        mov     dx, DAC_Y
        out     dx, ax
        mov     ax, [V_OUT + 4]
        mov     dx, DAC_T
        out     dx, ax
        ret

; ---- 锁定检测：三个误差都小于 LOCK_ERR，连续 LOCK_COUNT 次就报锁定 ----
lock_detect:
        mov     si, V_ERR
        mov     cx, 3
.chk:   lodsw
        or      ax, ax
        jns     .pos
        neg     ax
.pos:   cmp     ax, LOCK_ERR
        jae     .miss
        loop    .chk
        mov     ax, [V_LOCK]
        cmp     ax, LOCK_COUNT
        jae     .locked
        inc     word [V_LOCK]
        jmp     .report
.locked:
        mov     byte [V_STAT], 1
        jmp     .report
.miss:  mov     word [V_LOCK], 0
        mov     byte [V_STAT], 0
.report:
        mov     dx, STATUS
        mov     al, [V_STAT]
        out     dx, al
        ret

; ---- 上电入口（F000:FFF0）与日期、型号、校验和 ----
        times   0x1FF0 - ($ - $$) db 0xFF
reset_vector:
        jmp     0xF000:cold_start
        db      '11/16/26'
        db      0x00
        db      0xFE                    ; 型号字节（同 IBM PC/XT）
        db      0x00                    ; 校验和，由 laser_align_sum.py 补
