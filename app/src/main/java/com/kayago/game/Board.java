package com.kayago.game;

/**
 * 围棋棋盘（纯 Java，无任何 Android 依赖，方便在 JVM 上直接做单元测试）。
 *
 * <p>坐标使用“带边框”的内部索引：{@code p = (y + 1) * stride + (x + 1)}，
 * 合法落点满足 {@code 0 <= x, y < size}，边框外的点颜色为 {@link #WALL}。
 * 对外可用 {@link #point(int, int)} / {@link #x(int)} / {@link #y(int)} 换算。
 *
 * <p>棋块（chain）用单向链表维护：链头索引即该棋块的 id。配合“时间戳去重”的
 * 气数统计，落子 / 撤销的代价都在 O(棋块大小) 量级，可以支撑每秒数万次
 * 蒙特卡洛推演。
 *
 * <p>规则：禁止自杀（但“先提子后有气”合法）、简单劫（禁止立即提回）。
 * 位置超级劫由 {@link Game} 负责。
 */
public final class Board {

    public static final byte EMPTY = 0;
    public static final byte BLACK = 1;
    public static final byte WHITE = 2;
    public static final byte WALL = 3;

    /** 停一手。 */
    public static final int PASS = -1;

    private static final int MAX_STRIDE = 21; // 19x19 + 两边边框
    private static final long[] ZOBRIST = new long[MAX_STRIDE * MAX_STRIDE * 2];

    static {
        // 固定种子：保证不同 Board 实例的哈希一致。
        java.util.Random rnd = new java.util.Random(20260926L);
        for (int i = 0; i < ZOBRIST.length; i++) {
            ZOBRIST[i] = rnd.nextLong();
        }
    }

    private static final int REC = 7; // 撤销记录：点/颜色/劫/停手/提子起点/是否记录局面/合并块数

    public final int size;
    public final int stride;
    public final int total;   // stride * stride
    public final int points;  // size * size

    public final byte[] color;
    public final int[] cid;        // 棋块 id（链头索引），空点/边框为 -1
    public final int[] chainNext;  // 同一棋块中的下一颗子
    public final int[] chainSize;  // 仅链头有效
    public final int[] chainLibs;  // 仅链头有效
    public final int[] chainTail;  // 仅链头有效
    public final int[] nb;         // nb[p * 4 + d] -> 相邻点索引

    private final int[] libStamp;
    private int stamp;

    private final int[] emptyList;
    private final int[] emptyPos;
    private int emptyCount;

    private long hash;

    /** 简单劫禁着点，-1 表示没有。 */
    public int koPoint = -1;
    /** 连续停手次数。 */
    public int passes = 0;
    /** captured[c] = 颜色 c 被提掉的子数。 */
    public final int[] captured = new int[3];
    /** 已落子总数（撤销时会回退）。 */
    public int stones = 0;

    private int[] undoRec;
    private int undoPtr;
    private int[] capBuf;
    private int capPtr;

    private final int[] tmpCid = new int[8];
    /** insertStone 时合并了多少个相邻同色棋块（用于撤销时判断是否需要拆分）。 */
    private int lastMerges;
    private final int[] bfsStack;
    private final int[] bfsMark;
    private int bfsStamp;
    private final int[] rebuildBuf;
    private final byte[] rebuildCol;

    // ---- 位置超级劫用的开放寻址长整型集合 ----
    private long[] skey;
    private byte[] sstate; // 0 空 / 1 占用 / 2 墓碑
    private int[] scount;
    private int ssize;
    private int sused;

    public Board(int size) {
        this.size = size;
        this.stride = size + 2;
        this.total = stride * stride;
        this.points = size * size;

        color = new byte[total];
        cid = new int[total];
        chainNext = new int[total];
        chainSize = new int[total];
        chainLibs = new int[total];
        chainTail = new int[total];
        nb = new int[total * 4];
        libStamp = new int[total];
        emptyList = new int[points];
        emptyPos = new int[total];

        undoRec = new int[REC * (points + 8)];
        capBuf = new int[points];
        undoPtr = 0;
        capPtr = 0;

        bfsStack = new int[total];
        bfsMark = new int[total];
        bfsStamp = 0;
        rebuildBuf = new int[total];
        rebuildCol = new byte[total];
        initPositions();

        java.util.Arrays.fill(color, WALL);
        java.util.Arrays.fill(cid, -1);
        java.util.Arrays.fill(chainNext, -1);
        java.util.Arrays.fill(chainTail, -1);

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int p = (y + 1) * stride + (x + 1);
                color[p] = EMPTY;
                cid[p] = -1;
            }
        }
        for (int i = 0; i < total; i++) {
            int base = i * 4;
            nb[base] = (colorAt(i) == WALL) ? -1 : i - 1;
            nb[base + 1] = (colorAt(i) == WALL) ? -1 : i + 1;
            nb[base + 2] = (colorAt(i) == WALL) ? -1 : i - stride;
            nb[base + 3] = (colorAt(i) == WALL) ? -1 : i + stride;
        }

        emptyCount = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int p = (y + 1) * stride + (x + 1);
                emptyPos[p] = emptyCount;
                emptyList[emptyCount++] = p;
            }
        }
        hash = 0L;
        positionAdd(hash); // 记录初始（空盘）局面
    }

    private Board(Board src) {
        this.size = src.size;
        this.stride = src.stride;
        this.total = src.total;
        this.points = src.points;

        color = src.color.clone();
        cid = src.cid.clone();
        chainNext = src.chainNext.clone();
        chainSize = src.chainSize.clone();
        chainLibs = src.chainLibs.clone();
        chainTail = src.chainTail.clone();
        nb = src.nb; // 只读，可共享
        libStamp = new int[total];
        emptyList = src.emptyList.clone();
        emptyPos = src.emptyPos.clone();
        emptyCount = src.emptyCount;
        hash = src.hash;
        koPoint = src.koPoint;
        passes = src.passes;
        captured[0] = src.captured[0];
        captured[1] = src.captured[1];
        captured[2] = src.captured[2];
        stones = src.stones;
        undoRec = new int[REC * (points + 8)];
        undoPtr = 0;
        capBuf = new int[points];
        capPtr = 0;
        bfsStack = new int[total];
        bfsMark = new int[total];
        bfsStamp = 0;
        rebuildBuf = new int[total];
        rebuildCol = new byte[total];
        skey = src.skey.clone();
        sstate = src.sstate.clone();
        scount = src.scount.clone();
        ssize = src.ssize;
        sused = src.sused;
    }

    /** 深拷贝，用于多线程搜索。 */
    public Board copy() {
        return new Board(this);
    }

    // ------------------------------------------------------------------
    // 坐标换算
    // ------------------------------------------------------------------

    public int point(int x, int y) {
        return (y + 1) * stride + (x + 1);
    }

    public int x(int p) {
        return p % stride - 1;
    }

    public int y(int p) {
        return p / stride - 1;
    }

    /** 逻辑序号 -> 内部索引。 */
    public int fromIndex(int idx) {
        return (idx / size + 1) * stride + (idx % size + 1);
    }

    /** 内部索引 -> 逻辑序号（非合法点返回 -1）。 */
    public int toIndex(int p) {
        if (p < 0 || p >= total || color[p] == WALL) {
            return -1;
        }
        return (p / stride - 1) * size + (p % stride - 1);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public byte colorAt(int p) {
        return (p < 0 || p >= total) ? WALL : color[p];
    }

    public boolean isEmpty(int p) {
        return p >= 0 && p < total && color[p] == EMPTY;
    }

    public int nb(int p, int d) {
        return nb[p * 4 + d];
    }

    public int emptyCount() {
        return emptyCount;
    }

    public int emptyAt(int i) {
        return emptyList[i];
    }

    public long hash() {
        return hash;
    }

    /** 该点所属棋块的气数；空点返回 -1。 */
    public int chainLibsAt(int p) {
        int c = (p >= 0 && p < total) ? cid[p] : -1;
        return c < 0 ? -1 : chainLibs[c];
    }

    public int chainSizeAt(int p) {
        int c = (p >= 0 && p < total) ? cid[p] : -1;
        return c < 0 ? 0 : chainSize[c];
    }

    public static byte opposite(byte c) {
        return c == BLACK ? WHITE : BLACK;
    }

    /** 返回 p 所在棋块的任意一口气；p 不是棋子时返回 -1。 */
    public int firstLiberty(int p) {
        if (p < 0 || p >= total) {
            return -1;
        }
        int head = cid[p];
        if (head < 0) {
            return -1;
        }
        int s = head;
        while (s != -1) {
            for (int d = 0; d < 4; d++) {
                int q = nb[s * 4 + d];
                if (color[q] == EMPTY) {
                    return q;
                }
            }
            s = chainNext[s];
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 落子 / 撤销
    // ------------------------------------------------------------------

    /**
     * 这个点是否看起来是自己的眼（用于避免自填眼）。
     * 四正交必须是本方（边框视作本方），对角线对方子不超过 1 个。
     */
    public boolean isEyeLike(int p, byte col) {
        if (color[p] != EMPTY) {
            return false;
        }
        for (int d = 0; d < 4; d++) {
            int q = nb[p * 4 + d];
            byte c = color[q];
            if (c != col && c != WALL) {
                return false;
            }
        }
        byte opp = opposite(col);
        int oppDiag = 0;
        int offDiag = 0;
        for (int i = 0; i < 4; i++) {
            int dg;
            if (i == 0) {
                dg = p - stride - 1;
            } else if (i == 1) {
                dg = p - stride + 1;
            } else if (i == 2) {
                dg = p + stride - 1;
            } else {
                dg = p + stride + 1;
            }
            byte c = color[dg];
            if (c == WALL) {
                offDiag++;
            } else if (c == opp) {
                oppDiag++;
            }
        }
        if (offDiag == 0) {
            return oppDiag <= 1;
        }
        return oppDiag == 0;
    }

    /** 判断落子是否合法（不考虑超级劫）。 */
    public boolean isLegal(int p, byte col) {
        if (p == PASS) {
            return true;
        }
        if (p < 0 || p >= total || color[p] != EMPTY) {
            return false;
        }
        if (p == koPoint) {
            return false;
        }
        // 快速路径：只要还有一个空邻居，落子后该棋块必然有气，一定合法。
        if (nb[p * 4] >= 0 && color[nb[p * 4]] == EMPTY) {
            return true;
        }
        if (nb[p * 4 + 1] >= 0 && color[nb[p * 4 + 1]] == EMPTY) {
            return true;
        }
        if (nb[p * 4 + 2] >= 0 && color[nb[p * 4 + 2]] == EMPTY) {
            return true;
        }
        if (nb[p * 4 + 3] >= 0 && color[nb[p * 4 + 3]] == EMPTY) {
            return true;
        }

        byte opp = opposite(col);
        for (int d = 0; d < 4; d++) {
            int q = nb[p * 4 + d];
            if (color[q] == opp) {
                int c = cid[q];
                if (c >= 0 && chainLibs[c] == 1) {
                    return true; // 能提子，必然有气
                }
            }
        }
        return mergedLiberties(p, col) > 0;
    }

    /** 与 {@link #isLegal} 等价，语义更明确的名字，供搜索热路径使用。 */
    public boolean isLegalFast(int p, byte col) {
        return isLegal(p, col);
    }

    /**
     * 试下一手：合法且不违反位置超级劫时返回 true，并且不改变棋盘状态。
     * 根节点候选筛选用它，避免搜索给出会被规则拒绝的着法。
     */
    public boolean isPlayable(int p, byte col) {
        if (!play(p, col)) {
            return false;
        }
        undo();
        return true;
    }

    // ------------------------------------------------------------------
    // 位置超级劫：禁止“全局同形再现”（不含连续停手）
    // ------------------------------------------------------------------

    private void initPositions() {
        ssize = 2048;
        skey = new long[ssize];
        sstate = new byte[ssize];
        scount = new int[ssize];
        sused = 0;
    }

    private static int mix(long h) {
        long x = h;
        x ^= (x >>> 33);
        x *= 0xff51afd7ed558ccdL;
        x ^= (x >>> 33);
        return (int) x;
    }

    private void rehash() {
        long[] ok = skey;
        byte[] os = sstate;
        int[] oc = scount;
        int ns = ssize * 2;
        long[] nk = new long[ns];
        byte[] nst = new byte[ns];
        int[] nc = new int[ns];
        int mask = ns - 1;
        int used = 0;
        for (int i = 0; i < ok.length; i++) {
            if (os[i] != 1) {
                continue;
            }
            int j = mix(ok[i]) & mask;
            while (nst[j] != 0) {
                j = (j + 1) & mask;
            }
            nk[j] = ok[i];
            nst[j] = 1;
            nc[j] = oc[i];
            used++;
        }
        skey = nk;
        sstate = nst;
        scount = nc;
        ssize = ns;
        sused = used;
    }

    private void positionAdd(long h) {
        if ((sused + 1) * 10 >= ssize * 7) {
            rehash();
        }
        int mask = ssize - 1;
        int i = mix(h) & mask;
        int tomb = -1;
        while (sstate[i] != 0) {
            if (sstate[i] == 1) {
                if (skey[i] == h) {
                    scount[i]++;
                    return;
                }
            } else if (tomb < 0) {
                tomb = i;
            }
            i = (i + 1) & mask;
        }
        int slot = tomb >= 0 ? tomb : i;
        skey[slot] = h;
        sstate[slot] = 1;
        scount[slot] = 1;
        if (tomb < 0) {
            sused++;
        }
    }

    private void positionRemove(long h) {
        int mask = ssize - 1;
        int i = mix(h) & mask;
        while (sstate[i] != 0) {
            if (sstate[i] == 1 && skey[i] == h) {
                if (--scount[i] <= 0) {
                    sstate[i] = 2;
                }
                return;
            }
            i = (i + 1) & mask;
        }
    }

    private boolean positionContains(long h) {
        int mask = ssize - 1;
        int i = mix(h) & mask;
        while (sstate[i] != 0) {
            if (sstate[i] == 1 && skey[i] == h) {
                return true;
            }
            i = (i + 1) & mask;
        }
        return false;
    }

    /** 假设在 p 落子并与相邻同色棋块连成一体后，该棋块的气数（不改变棋盘）。 */
    private int mergedLiberties(int p, byte col) {
        stamp++;
        int cnt = 0;
        for (int d = 0; d < 4; d++) {
            int q = nb[p * 4 + d];
            byte c = color[q];
            if (c == EMPTY) {
                // 注意：p 自己也是空的，但它马上就要落子，不能算作气。
                if (q != p && libStamp[q] != stamp) {
                    libStamp[q] = stamp;
                    cnt++;
                }
            } else if (c == col) {
                int head = cid[q];
                int s = head;
                while (s != -1) {
                    for (int e = 0; e < 4; e++) {
                        int r = nb[s * 4 + e];
                        if (color[r] == EMPTY && r != p && libStamp[r] != stamp) {
                            libStamp[r] = stamp;
                            cnt++;
                        }
                    }
                    s = chainNext[s];
                }
            }
        }
        return cnt;
    }

    /**
     * 落子。非法返回 false 且棋盘不变。
     */
    public boolean play(int p, byte col) {
        if (!isLegal(p, col)) {
            return false;
        }
        int rec = undoPtr * REC;
        if (rec + REC > undoRec.length) {
            undoRec = java.util.Arrays.copyOf(undoRec, undoRec.length * 2);
        }
        undoRec[rec] = p;
        undoRec[rec + 1] = col;
        undoRec[rec + 2] = koPoint;
        undoRec[rec + 3] = passes;
        undoRec[rec + 4] = capPtr;
        undoRec[rec + 5] = 1;
        undoRec[rec + 6] = 0;
        undoPtr++;

        if (p == PASS) {
            koPoint = -1;
            passes++;
            positionAdd(hash);
            return true;
        }

        int capStart = capPtr;
        insertStone(p, col);
        undoRec[rec + 6] = lastMerges;
        captureAdjacent(p, col);
        // 自己这块棋一定变了，重算一次；相邻敌方棋块的气数在 captureAdjacent 里做了
        // 增量维护（落子点原本就是它们的口气），不需要再全量扫描。
        chainLibs[cid[p]] = countLibs(cid[p]);
        // 被提子点变空，周围“别的己方棋块”因此多出了气，需要重算。
        for (int i = capStart; i < capPtr; i++) {
            int q = capBuf[i];
            for (int d = 0; d < 4; d++) {
                int r = nb[q * 4 + d];
                if (color[r] == col) {
                    int cc = cid[r];
                    if (cc >= 0 && cc != cid[p]) {
                        chainLibs[cc] = countLibs(cc);
                    }
                }
            }
        }

        // 位置超级劫：禁止全局同形再现
        if (positionContains(hash)) {
            undoRec[rec + 5] = 0; // 本次没有记录局面，撤销时不要弹
            undo();
            return false;
        }

        koPoint = -1;
        int capCount = capPtr - capStart;
        if (capCount == 1 && chainSize[cid[p]] == 1 && chainLibs[cid[p]] == 1) {
            koPoint = capBuf[capStart];
        }
        passes = 0;
        positionAdd(hash);
        return true;
    }

    /** 撤销最近一手（必须在有历史时调用）。 */
    public void undo() {
        if (undoPtr == 0) {
            return;
        }
        undoPtr--;
        int rec = undoPtr * REC;
        int p = undoRec[rec];
        byte col = (byte) undoRec[rec + 1];
        int prevKo = undoRec[rec + 2];
        int prevPasses = undoRec[rec + 3];
        int capStart = undoRec[rec + 4];
        boolean posRecorded = undoRec[rec + 5] == 1;
        int merges = undoRec[rec + 6];

        if (posRecorded) {
            positionRemove(hash);
        }

        if (p == PASS) {
            koPoint = prevKo;
            passes = prevPasses;
            return;
        }

        byte capColor = opposite(col);
        int capCount = capPtr - capStart;

        // 摘掉落下的这颗子。它是“接点”：只有当它把 2 块以上己方棋连起来时，
        // 摘掉后才可能裂开，这时才需要检查并重建该棋块。
        int head = cid[p];
        int expected = chainSize[head] - 1;
        int newHead = unlinkStone(p);
        if (merges >= 2 && expected > 0 && !isChainConnected(newHead, expected)) {
            rebuildChain(newHead);
        }

        // 放回被提的子（单独插入会自动与相邻同色重新连成一块）
        for (int i = capStart; i < capStart + capCount; i++) {
            restoreStone(capBuf[i], capColor);
        }
        if (capCount > 0) {
            captured[capColor] -= capCount;
        }

        recomputeAround(p);
        for (int i = capStart; i < capStart + capCount; i++) {
            recomputeAround(capBuf[i]);
        }
        capPtr = capStart;
        koPoint = prevKo;
        passes = prevPasses;
    }

    // ------------------------------------------------------------------
    // 内部结构维护
    // ------------------------------------------------------------------

    /**
     * 把 p 从所在棋块中摘掉（p 可能在链头也可能在链中间），返回摘除后该棋块的新链头
     * （棋块为空则返回 -1）。此时链表仍然完整，但“链表 = 连通块”这一不变式可能被破坏，
     * 需要用 {@link #isChainConnected} 校验。
     */
    private int unlinkStone(int p) {
        hash ^= ZOBRIST[p * 2 + (color[p] - 1)];
        int head = cid[p];
        int newHead;
        if (head == p) {
            int rest = chainNext[p];
            if (rest != -1) {
                int oldSize = chainSize[p];
                int tail = chainTail[p];
                int s = rest;
                while (s != -1) {
                    cid[s] = rest;
                    s = chainNext[s];
                }
                chainSize[rest] = oldSize - 1;
                chainTail[rest] = tail;
                chainLibs[rest] = 0;
            }
            chainSize[p] = 0;
            chainLibs[p] = 0;
            chainTail[p] = -1;
            newHead = rest;
        } else {
            int s = head;
            while (chainNext[s] != p) {
                s = chainNext[s];
            }
            chainNext[s] = chainNext[p];
            if (chainTail[head] == p) {
                chainTail[head] = s;
            }
            chainSize[head]--;
            chainLibs[head] = 0;
            newHead = head;
        }
        color[p] = EMPTY;
        cid[p] = -1;
        chainNext[p] = -1;
        stones--;
        emptyPos[p] = emptyCount;
        emptyList[emptyCount++] = p;
        return newHead;
    }

    /** 从 head 出发做一次同色广度遍历，判断该棋块是否仍是一个连通块。 */
    private boolean isChainConnected(int head, int expected) {
        if (head < 0) {
            return expected == 0;
        }
        bfsStamp++;
        int sp = 0;
        bfsStack[sp++] = head;
        bfsMark[head] = bfsStamp;
        int count = 0;
        while (sp > 0) {
            int q = bfsStack[--sp];
            count++;
            for (int d = 0; d < 4; d++) {
                int r = nb[q * 4 + d];
                if (bfsMark[r] != bfsStamp) {
                    byte c = color[r];
                    if (c == BLACK || c == WHITE) {
                        bfsMark[r] = bfsStamp;
                        bfsStack[sp++] = r;
                    }
                }
            }
        }
        return count == expected;
    }

    /** 把 head 所在的链表整体拆掉再逐颗放回，从而恢复正确的棋块划分。 */
    private void rebuildChain(int head) {
        int cnt = 0;
        int s = head;
        while (s != -1) {
            rebuildBuf[cnt] = s;
            rebuildCol[cnt] = color[s];
            cnt++;
            s = chainNext[s];
        }
        for (int i = 0; i < cnt; i++) {
            int q = rebuildBuf[i];
            hash ^= ZOBRIST[q * 2 + (color[q] - 1)];
            color[q] = EMPTY;
            cid[q] = -1;
            chainNext[q] = -1;
            chainSize[q] = 0;
            chainLibs[q] = 0;
            chainTail[q] = -1;
            stones--;
            emptyPos[q] = emptyCount;
            emptyList[emptyCount++] = q;
        }
        for (int i = 0; i < cnt; i++) {
            insertStone(rebuildBuf[i], rebuildCol[i]);
        }
        for (int i = 0; i < cnt; i++) {
            int q = rebuildBuf[i];
            if (cid[q] == q) {
                countLibs(q);
            }
        }
    }

    private void insertStone(int p, byte col) {
        color[p] = col;
        cid[p] = p;
        chainNext[p] = -1;
        chainTail[p] = p;
        chainSize[p] = 1;
        chainLibs[p] = 0;
        stones++;
        hash ^= ZOBRIST[p * 2 + (col - 1)];
        lastMerges = 0;

        int pos = emptyPos[p];
        int last = emptyList[--emptyCount];
        emptyList[pos] = last;
        emptyPos[last] = pos;

        for (int d = 0; d < 4; d++) {
            int q = nb[p * 4 + d];
            if (color[q] == col) {
                int c = cid[q];
                if (c != p && c >= 0) {
                    mergeInto(p, c);
                    lastMerges++;
                }
            }
        }
    }

    /** 撤销时使用：不检查合法性，把被提的子放回棋盘。 */
    private void restoreStone(int p, byte col) {
        insertStone(p, col);
    }

    private void mergeInto(int head, int other) {
        chainNext[chainTail[head]] = other;
        chainTail[head] = chainTail[other];
        int s = other;
        while (s != -1) {
            cid[s] = head;
            s = chainNext[s];
        }
        chainSize[head] += chainSize[other];
    }

    /** 重算棋块 head 的气数。 */
    private int countLibs(int head) {
        stamp++;
        int cnt = 0;
        int s = head;
        while (s != -1) {
            for (int d = 0; d < 4; d++) {
                int q = nb[s * 4 + d];
                if (color[q] == EMPTY && libStamp[q] != stamp) {
                    libStamp[q] = stamp;
                    cnt++;
                }
            }
            s = chainNext[s];
        }
        chainLibs[head] = cnt;
        return cnt;
    }

    /** 提掉气数为 0 的相邻敌方块：相邻敌块的气数恰好 -1（落子点原本就是它们的口气）。 */
    private void captureAdjacent(int p, byte col) {
        byte opp = opposite(col);
        int n = 0;
        for (int d = 0; d < 4; d++) {
            int q = nb[p * 4 + d];
            if (color[q] == opp) {
                int c = cid[q];
                if (c < 0) {
                    continue;
                }
                boolean seen = false;
                for (int i = 0; i < n; i++) {
                    if (tmpCid[i] == c) {
                        seen = true;
                        break;
                    }
                }
                if (!seen && n < tmpCid.length) {
                    tmpCid[n++] = c;
                }
            }
        }
        for (int i = 0; i < n; i++) {
            int c = tmpCid[i];
            if (chainLibs[c] > 0) {
                chainLibs[c]--;
            }
            if (chainLibs[c] == 0) {
                removeChain(c);
            }
        }
    }

    private void removeChain(int head) {
        byte removedColor = color[head];
        int s = head;
        while (s != -1) {
            int next = chainNext[s];
            hash ^= ZOBRIST[s * 2 + (removedColor - 1)];
            color[s] = EMPTY;
            cid[s] = -1;
            stones--;
            captured[removedColor]++;
            emptyPos[s] = emptyCount;
            emptyList[emptyCount++] = s;
            if (capPtr >= capBuf.length) {
                capBuf = java.util.Arrays.copyOf(capBuf, capBuf.length * 2);
            }
            capBuf[capPtr++] = s;
            s = next;
        }
    }

    /** 重算 p 及 p 的相邻棋块的气数。 */
    private void recomputeAround(int p) {
        int n = 0;
        for (int d = 0; d < 4; d++) {
            int q = nb[p * 4 + d];
            byte c = color[q];
            if (c == EMPTY || c == WALL) {
                continue;
            }
            int id = cid[q];
            if (id < 0) {
                continue;
            }
            boolean seen = false;
            for (int i = 0; i < n; i++) {
                if (tmpCid[i] == id) {
                    seen = true;
                    break;
                }
            }
            if (!seen && n < tmpCid.length) {
                tmpCid[n++] = id;
            }
        }
        byte c = color[p];
        if (c != EMPTY && c != WALL && cid[p] >= 0) {
            countLibs(cid[p]);
        }
        for (int i = 0; i < n; i++) {
            if (cid[tmpCid[i]] == tmpCid[i]) {
                countLibs(tmpCid[i]);
            }
        }
    }

    /** 调试/测试用：校验所有棋块的气数缓存与实时计算一致。 */
    public boolean validate() {
        boolean ok = true;
        for (int p = 0; p < total; p++) {
            if (color[p] == EMPTY || color[p] == WALL) {
                continue;
            }
            int c = cid[p];
            if (c < 0 || color[c] == EMPTY) {
                ok = false;
                continue;
            }
            int real = rawLiberties(c);
            if (real != chainLibs[c]) {
                ok = false;
            }
            if (real == 0) {
                ok = false; // 棋盘上不该存在没有气的棋块
            }
        }
        return ok;
    }

    /** 不含缓存的实时气数计算。 */
    private int rawLiberties(int head) {
        stamp++;
        int cnt = 0;
        int s = head;
        while (s != -1) {
            for (int d = 0; d < 4; d++) {
                int q = nb[s * 4 + d];
                if (color[q] == EMPTY && libStamp[q] != stamp) {
                    libStamp[q] = stamp;
                    cnt++;
                }
            }
            s = chainNext[s];
        }
        return cnt;
    }
}
