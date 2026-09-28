package com.yk.xiangqi.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 以当前行棋方归一化保存的象棋棋盘。{@code ours}/{@code theirs} 表示归属，
 * 各兵种位板与归属位板彼此独立。每步走完调用 {@link
 * #mirror()}，因此走法生成始终只写一方视角。
 */
public final class Board {
    public static final int FILES = 9, RANKS = 10, SQUARES = 90;
    private BitBoard ours = BitBoard.EMPTY, theirs = BitBoard.EMPTY;
    private BitBoard rooks = BitBoard.EMPTY, advisors = BitBoard.EMPTY, cannons = BitBoard.EMPTY, pawns = BitBoard.EMPTY,
        knights = BitBoard.EMPTY, bishops = BitBoard.EMPTY;
    private byte ourKing = -1, theirKing = -1;
    private boolean flipped;
    /**
     * 每方稳定的棋子身份，仅供规则裁决使用。
     */
    private final byte[] ruleId = new byte[SQUARES];

    public Board() {
    }

    public Board(Board source) {
        ours = source.ours;
        theirs = source.theirs;
        rooks = source.rooks;
        advisors = source.advisors;
        cannons = source.cannons;
        pawns = source.pawns;
        knights = source.knights;
        bishops = source.bishops;
        ourKing = source.ourKing;
        theirKing = source.theirKing;
        flipped = source.flipped;
        System.arraycopy(source.ruleId, 0, ruleId, 0, SQUARES);
    }

    public BitBoard ours() {
        return ours;
    }

    public BitBoard theirs() {
        return theirs;
    }

    public BitBoard occupied() {
        return ours.or(theirs);
    }

    public BitBoard rooks() {
        return rooks;
    }

    public BitBoard advisors() {
        return advisors;
    }

    public BitBoard cannons() {
        return cannons;
    }

    public BitBoard pawns() {
        return pawns;
    }

    public BitBoard knights() {
        return knights;
    }

    public BitBoard bishops() {
        return bishops;
    }

    public int ourKing() {
        return ourKing;
    }

    public int theirKing() {
        return theirKing;
    }

    public boolean flipped() {
        return flipped;
    }

    /**
     * FEN 建盘后为双方分别分配 0..15 的棋子身份。
     */
    public void initializeRuleIds() {
        int oursId = 0;
        int theirsId = 0;
        for (int square : occupied()) {
            ruleId[square] = (byte) (ours.contains(square) ? oursId++ : theirsId++);
        }
    }

    public boolean sameState(Board other) {
        return ours.equals(other.ours) && theirs.equals(other.theirs) && rooks.equals(other.rooks) && advisors.equals(other.advisors)
            && cannons.equals(other.cannons) && pawns.equals(other.pawns) && knights.equals(other.knights) && bishops.equals(other.bishops)
            && ourKing == other.ourKing && theirKing == other.theirKing && flipped == other.flipped;
    }

    public void put(int square, PieceType piece, boolean isTheirs) {
        requireSquare(square);
        if (occupied().contains(square))
            throw new IllegalArgumentException("occupied square: " + square);
        if (piece == PieceType.KING) {
            if (isTheirs ? theirKing >= 0 : ourKing >= 0)
                throw new IllegalArgumentException("duplicate king");
            if (isTheirs)
                theirKing = (byte) square;
            else
                ourKing = (byte) square;
        } else
            setKind(piece, square, true);
        if (isTheirs)
            theirs = theirs.with(square);
        else
            ours = ours.with(square);
    }

    public PieceType remove(int square) {
        requireSquare(square);
        if (!occupied().contains(square))
            return null;
        boolean enemy = theirs.contains(square);
        PieceType piece = pieceAt(square);
        if (enemy)
            theirs = theirs.without(square);
        else
            ours = ours.without(square);
        if (piece == PieceType.KING) {
            if (enemy)
                theirKing = -1;
            else
                ourKing = -1;
        } else
            setKind(piece, square, false);
        return piece;
    }

    /**
     * 在归一化坐标中执行一着已验证的合法棋。
     */
    public boolean apply(Move move) {
        if (!ours.contains(move.from) || move.to == theirKing)
            throw new IllegalArgumentException("invalid board move: " + move);
        boolean captured = theirs.contains(move.to);
        PieceType moving = remove(move.from);
        remove(move.to);
        put(move.to, moving, false);
        int fromId = flipped ? flipRank(move.from) : move.from;
        int toId = flipped ? flipRank(move.to) : move.to;
        ruleId[toId] = ruleId[fromId];
        ruleId[fromId] = 0;
        return captured;
    }

    /**
     * 翻转纵线并交换归属集合，使下一方再次成为 {@code ours}。
     */
    public void mirror() {
        BitBoard oldOurs = ours.mirrorRanks();
        ours = theirs.mirrorRanks();
        theirs = oldOurs;
        rooks = rooks.mirrorRanks();
        advisors = advisors.mirrorRanks();
        cannons = cannons.mirrorRanks();
        pawns = pawns.mirrorRanks();
        knights = knights.mirrorRanks();
        bishops = bishops.mirrorRanks();
        byte oldKing = (byte) flipRank(ourKing);
        ourKing = (byte) flipRank(theirKing);
        theirKing = oldKing;
        flipped = !flipped;
    }

    public PieceType pieceAt(int square) {
        requireSquare(square);
        if (!occupied().contains(square))
            return null;
        if (rooks.contains(square))
            return PieceType.ROOK;
        if (advisors.contains(square))
            return PieceType.ADVISOR;
        if (cannons.contains(square))
            return PieceType.CANNON;
        if (pawns.contains(square))
            return PieceType.PAWN;
        if (knights.contains(square))
            return PieceType.KNIGHT;
        if (bishops.contains(square))
            return PieceType.BISHOP;
        if (square == ourKing || square == theirKing)
            return PieceType.KING;
        throw new IllegalStateException("piece marker missing at " + square);
    }

    public List<Move> pseudoLegalMoves() {
        BitBoard occupied = occupied();
        List<Move> result = new ArrayList<>(48);
        for (int from : ours) {
            BitBoard targets = switch (pieceAt(from)) {
                case ROOK -> Attacks.rook(from, occupied);
                case CANNON -> Attacks.cannonQuiet(from, occupied).or(Attacks.cannonCapture(from, occupied));
                case KNIGHT -> Attacks.knight(from, occupied);
                case BISHOP -> Attacks.bishop(from, occupied);
                case ADVISOR -> Attacks.ADVISOR[from];
                case KING -> Attacks.KING[from];
                case PAWN -> Attacks.PAWN[from];
            };
            for (int to : targets.minus(ours))
                if (to != theirKing)
                    result.add(new Move(from, to));
        }
        return result;
    }

    public List<Move> legalMoves() {
        List<Move> result = new ArrayList<>();
        for (Move m : pseudoLegalMoves())
            if (isLegal(m))
                result.add(m);
        return result;
    }

    public boolean isLegal(Move move) {
        if (!isPseudoLegal(move))
            return false;
        PieceType moving = pieceAt(move.from);
        BitBoard after = occupied().without(move.from).with(move.to);
        int king = moving == PieceType.KING ? move.to : ourKing;
        if (king < 0 || theirKing < 0 || Attacks.rook(king, after).contains(theirKing))
            return false;
        // 兵种标记仍是走前棋盘；被吃的敌子可能仍被识别为将军子。
        // 只移除落点这一标记，随后要求其余将军子为空。
        return checkersTo(king, after, theirs).without(move.to).isEmpty();
    }

    /**
     * 只验证走法的棋子几何与目标归属；不检查走后本方将是否受攻击。
     *
     * <p>合法着生成会先产生伪合法着，再逐一调用 {@link #isLegal(Move)}。这里必须直接查询攻击表，
     * 不能为每一着重新构造整张伪合法着表。</p>
     */
    private boolean isPseudoLegal(Move move) {
        if (!ours.contains(move.from) || ours.contains(move.to) || move.to == theirKing)
            return false;
        BitBoard occupancy = occupied();
        PieceType piece = pieceAt(move.from);
        BitBoard targets = piece == PieceType.CANNON
            ? Attacks.cannonQuiet(move.from, occupancy).or(Attacks.cannonCapture(move.from, occupancy))
            : attacks(piece, move.from, occupancy);
        return targets.contains(move.to);
    }

    public boolean underCheck() {
        return ourKing < 0 || !checkersTo(ourKing, occupied(), theirs).isEmpty();
    }

    /**
     * 当前方长捉目标，以稳定棋子身份的位集合表示。
     */
    public long usChased() {
        long chase = 0;
        chase |= chaseBy(PieceType.ROOK, rooks);
        chase |= chaseBy(PieceType.ADVISOR, advisors);
        chase |= chaseBy(PieceType.CANNON, cannons);
        chase |= chaseBy(PieceType.KNIGHT, knights);
        chase |= chaseBy(PieceType.BISHOP, bishops);
        return chase;
    }

    /**
     * 对方长捉目标；不修改当前棋盘。
     */
    public long themChased() {
        Board mirrored = new Board(this);
        mirrored.mirror();
        return mirrored.usChased();
    }

    private long chaseBy(PieceType attackerType, BitBoard attackers) {
        long result = 0;
        BitBoard occupancy = occupied();
        for (int from : attackers.and(ours)) {
            BitBoard targets = attacks(attackerType, from, occupancy).and(theirs);
            targets = targets.without(theirKing);
            for (int pawn : pawns.and(theirs))
                if (rank(pawn) >= 5)
                    targets = targets.without(pawn);

            BitBoard candidates = BitBoard.EMPTY;
            if (attackerType == PieceType.KNIGHT || attackerType == PieceType.CANNON)
                candidates = targets.and(rooks);
            if (attackerType == PieceType.ADVISOR || attackerType == PieceType.BISHOP)
                candidates = targets.and(rooks.or(knights).or(cannons));

            for (int to : candidates) {
                Move move = new Move(from, to);
                if (isLegal(move))
                    result |= chaseMask(to);
            }
            targets = targets.minus(candidates);
            for (int to : targets) {
                Move move = new Move(from, to);
                if (!isLegal(move))
                    continue;
                Board after = new Board(this);
                after.apply(move);
                boolean trueChase = true;
                for (int recapture : after.recapturesTo(to)) {
                    if (after.isLegalForTheirs(new Move(recapture, to))) {
                        trueChase = false;
                        break;
                    }
                }
                if (!trueChase)
                    continue;
                if (attackers.contains(to)) {
                    boolean pinnedKnight = attackerType == PieceType.KNIGHT && !Attacks.knight(to, occupancy).contains(from);
                    if (pinnedKnight || !isLegalForTheirs(new Move(to, from)))
                        result |= chaseMask(to);
                } else
                    result |= chaseMask(to);
            }
        }
        return result;
    }

    private BitBoard recapturesTo(int square) {
        Board mirrored = new Board(this);
        mirrored.mirror();
        BitBoard result = mirrored.attackersToOurs(flipRank(square));
        return result.mirrorRanks();
    }

    private BitBoard attackersToOurs(int target) {
        BitBoard occupancy = occupied();
        BitBoard result = Attacks.rook(target, occupancy).and(rooks);
        result = result.or(Attacks.ADVISOR[target].and(advisors));
        result = result.or(Attacks.cannonCapture(target, occupancy).and(cannons));
        result = result.or(Attacks.PAWN_FROM_OURS[target].and(pawns));
        result = result.or(Attacks.knightTo(target, occupancy).and(knights));
        result = result.or(Attacks.bishop(target, occupancy).and(bishops));
        if (Attacks.KING[target].contains(ourKing))
            result = result.with(ourKing);
        return result.and(ours);
    }

    private boolean isLegalForTheirs(Move move) {
        Board mirrored = new Board(this);
        mirrored.mirror();
        return mirrored.isLegal(flipRank(move));
    }

    private long chaseMask(int normalizedSquare) {
        int absolute = flipped ? flipRank(normalizedSquare) : normalizedSquare;
        return 1L << Byte.toUnsignedInt(ruleId[absolute]);
    }

    private static BitBoard attacks(PieceType piece, int from, BitBoard occupancy) {
        return switch (piece) {
            case ROOK -> Attacks.rook(from, occupancy);
            case ADVISOR -> Attacks.ADVISOR[from];
            case CANNON -> Attacks.cannonCapture(from, occupancy);
            case KNIGHT -> Attacks.knight(from, occupancy);
            case BISHOP -> Attacks.bishop(from, occupancy);
            case KING -> Attacks.KING[from];
            case PAWN -> Attacks.PAWN[from];
        };
    }

    private BitBoard checkersTo(int target, BitBoard occupancy, BitBoard attackers) {
        BitBoard result = Attacks.rook(target, occupancy).and(rooks);
        result = result.or(Attacks.cannonCapture(target, occupancy).and(cannons));
        result = result.or(Attacks.knightTo(target, occupancy).and(knights));
        return result.or(Attacks.PAWN_TO_OURS[target].and(pawns)).and(attackers);
    }

    private void setKind(PieceType piece, int square, boolean set) {
        BitBoard value = switch (piece) {
            case ROOK -> rooks;
            case ADVISOR -> advisors;
            case CANNON -> cannons;
            case PAWN -> pawns;
            case KNIGHT -> knights;
            case BISHOP -> bishops;
            case KING -> throw new IllegalArgumentException("king marker");
        };
        value = set ? value.with(square) : value.without(square);
        switch (piece) {
            case ROOK -> rooks = value;
            case ADVISOR -> advisors = value;
            case CANNON -> cannons = value;
            case PAWN -> pawns = value;
            case KNIGHT -> knights = value;
            case BISHOP -> bishops = value;
            case KING -> throw new AssertionError();
        }
    }

    public static int file(int square) {
        return square % FILES;
    }

    public static int rank(int square) {
        return square / FILES;
    }

    public static int square(int file, int rank) {
        return rank * FILES + file;
    }

    public static int flipRank(int square) {
        return square < 0 ? -1 : square(file(square), RANKS - 1 - rank(square));
    }

    public static Move flipRank(Move move) {
        return new Move(flipRank(move.from), flipRank(move.to));
    }

    private static void requireSquare(int square) {
        if (square < 0 || square >= SQUARES)
            throw new IllegalArgumentException("invalid square: " + square);
    }

    /**
     * 两个 long 组成的 u128 风格位棋盘，90..127 位恒为零。
     */
    public static final class BitBoard implements Iterable<Integer> {
        public static final BitBoard EMPTY = new BitBoard(0, 0);
        private static final long TOP_MASK = (1L << 26) - 1;
        private final long low, high;

        private BitBoard(long low, long high) {
            this.low = low;
            this.high = high & TOP_MASK;
        }

        public boolean contains(int square) {
            requireSquare(square);
            return square < 64 ? (low & (1L << square)) != 0 : (high & (1L << (square - 64))) != 0;
        }

        public boolean isEmpty() {
            return low == 0 && high == 0;
        }

        public int count() {
            return Long.bitCount(low) + Long.bitCount(high);
        }

        public BitBoard with(int square) {
            requireSquare(square);
            return square < 64 ? new BitBoard(low | 1L << square, high) : new BitBoard(low, high | 1L << (square - 64));
        }

        public BitBoard without(int square) {
            requireSquare(square);
            return square < 64 ? new BitBoard(low & ~(1L << square), high) : new BitBoard(low, high & ~(1L << (square - 64)));
        }

        public BitBoard or(BitBoard other) {
            return new BitBoard(low | other.low, high | other.high);
        }

        public BitBoard and(BitBoard other) {
            return new BitBoard(low & other.low, high & other.high);
        }

        public BitBoard minus(BitBoard other) {
            return new BitBoard(low & ~other.low, high & ~other.high);
        }

        public BitBoard mirrorRanks() {
            BitBoard result = EMPTY;
            for (int sq : this) result = result.with(flipRank(sq));
            return result;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BitBoard board && low == board.low && high == board.high;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(low) * 31 + Long.hashCode(high);
        }

        @Override
        public Iterator<Integer> iterator() {
            return new Iterator<>() {
                long a = low, b = high;

                public boolean hasNext() {
                    return a != 0 || b != 0;
                }

                public Integer next() {
                    if (a != 0) {
                        int s = Long.numberOfTrailingZeros(a);
                        a &= a - 1;
                        return s;
                    }
                    int s = Long.numberOfTrailingZeros(b);
                    b &= b - 1;
                    return 64 + s;
                }
            };
        }
    }

    /**
     * 固定几何攻击表只生成一次；滑子查询只读取占位位板，不依赖邮箱数组。
     *
     * <p>这里按射线逐格扫描，并非极限性能的预计算查表方案；但对象不分配、棋盘仅 90 格，
     * 对界面、规则校验与本项目的引擎交互已足够快，且实现更直接、更易验证。</p>
     */
    private static final class Attacks {
        static final int[][][] RAYS = new int[SQUARES][4][];
        static final Jump[][] KNIGHT = new Jump[SQUARES][], KNIGHT_TO = new Jump[SQUARES][], BISHOP = new Jump[SQUARES][];
        static final BitBoard[] ADVISOR = new BitBoard[SQUARES], KING = new BitBoard[SQUARES], PAWN = new BitBoard[SQUARES],
            PAWN_TO_OURS = new BitBoard[SQUARES], PAWN_FROM_OURS = new BitBoard[SQUARES];

        static {
            for (int s = 0; s < SQUARES; s++) {
                int x = file(s), y = rank(s);
                RAYS[s][0] = ray(x, y, 0, 1);
                RAYS[s][1] = ray(x, y, 0, -1);
                RAYS[s][2] = ray(x, y, 1, 0);
                RAYS[s][3] = ray(x, y, -1, 0);
                KNIGHT[s] = jumps(x, y);
                KNIGHT_TO[s] = jumpsTo(x, y);
                BISHOP[s] = bishop(x, y);
                ADVISOR[s] = palace(x, y, new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}});
                KING[s] = palace(x, y, new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}});
                PAWN[s] = pawnTargets(x, y);
                PAWN_TO_OURS[s] = enemyPawnSources(x, y);
                PAWN_FROM_OURS[s] = ourPawnSources(x, y);
            }
        }

        static BitBoard rook(int from, BitBoard occupied) {
            return slider(from, occupied, 0, false);
        }

        static BitBoard cannonQuiet(int from, BitBoard occupied) {
            return slider(from, occupied, 0, true);
        }

        static BitBoard cannonCapture(int from, BitBoard occupied) {
            return slider(from, occupied, 1, false);
        }

        static BitBoard knight(int from, BitBoard occupied) {
            return jumps(KNIGHT[from], occupied);
        }

        static BitBoard knightTo(int to, BitBoard occupied) {
            return jumps(KNIGHT_TO[to], occupied);
        }

        static BitBoard bishop(int from, BitBoard occupied) {
            return jumps(BISHOP[from], occupied);
        }

        static BitBoard slider(int from, BitBoard occupied, int blockersNeeded, boolean quietOnly) {
            BitBoard result = BitBoard.EMPTY;
            for (int[] ray : RAYS[from]) {
                int blockers = 0;
                for (int to : ray) {
                    if (!occupied.contains(to)) {
                        if (blockers == blockersNeeded && (quietOnly || blockersNeeded == 0))
                            result = result.with(to);
                    } else {
                        if (blockers == blockersNeeded && !quietOnly)
                            result = result.with(to);
                        if (++blockers > blockersNeeded)
                            break;
                    }
                }
            }
            return result;
        }

        static BitBoard jumps(Jump[] jumps, BitBoard occupied) {
            BitBoard result = BitBoard.EMPTY;
            for (Jump jump : jumps)
                if (!occupied.contains(jump.leg))
                    result = result.with(jump.to);
            return result;
        }

        static int[] ray(int x, int y, int dx, int dy) {
            int n = 0;
            for (int a = x + dx, b = y + dy; inside(a, b); a += dx, b += dy) n++;
            int[] out = new int[n];
            int i = 0;
            for (int a = x + dx, b = y + dy; inside(a, b); a += dx, b += dy) out[i++] = square(a, b);
            return out;
        }

        static Jump[] jumps(int x, int y) {
            int[][] d = {
                {1, 2, 0, 1}, {2, 1, 1, 0}, {2, -1, 1, 0}, {1, -2, 0, -1}, {-1, -2, 0, -1}, {-2, -1, -1, 0}, {-2, 1, -1, 0}, {-1, 2, 0, 1}};
            List<Jump> out = new ArrayList<>();
            for (int[] m : d)
                if (inside(x + m[0], y + m[1]))
                    out.add(new Jump(square(x + m[0], y + m[1]), square(x + m[2], y + m[3])));
            return out.toArray(new Jump[0]);
        }

        static Jump[] jumpsTo(int x, int y) {
            int[][] d = {
                {1, 2, 0, 1}, {2, 1, 1, 0}, {2, -1, 1, 0}, {1, -2, 0, -1}, {-1, -2, 0, -1}, {-2, -1, -1, 0}, {-2, 1, -1, 0}, {-1, 2, 0, 1}};
            List<Jump> out = new ArrayList<>();
            for (int[] m : d) {
                int sx = x - m[0], sy = y - m[1];
                if (inside(sx, sy))
                    out.add(new Jump(square(sx, sy), square(sx + m[2], sy + m[3])));
            }
            return out.toArray(new Jump[0]);
        }

        static Jump[] bishop(int x, int y) {
            List<Jump> out = new ArrayList<>();
            for (int dx : new int[]{-2, 2})
                for (int dy : new int[]{-2, 2}) {
                    int tx = x + dx, ty = y + dy;
                    if (inside(tx, ty) && ty <= 4)
                        out.add(new Jump(square(tx, ty), square(x + dx / 2, y + dy / 2)));
                }
            return out.toArray(new Jump[0]);
        }

        static BitBoard palace(int x, int y, int[][] steps) {
            BitBoard out = BitBoard.EMPTY;
            for (int[] d : steps) {
                int tx = x + d[0], ty = y + d[1];
                if (tx >= 3 && tx <= 5 && ty >= 0 && ty <= 2)
                    out = out.with(square(tx, ty));
            }
            return out;
        }

        static BitBoard pawnTargets(int x, int y) {
            BitBoard out = BitBoard.EMPTY;
            if (y < 9)
                out = out.with(square(x, y + 1));
            if (y >= 5) {
                if (x > 0)
                    out = out.with(square(x - 1, y));
                if (x < 8)
                    out = out.with(square(x + 1, y));
            }
            return out;
        }

        /**
         * 归一化棋盘中，对方兵向下行走。
         */
        static BitBoard enemyPawnSources(int x, int y) {
            BitBoard out = BitBoard.EMPTY;
            if (y < 9)
                out = out.with(square(x, y + 1));
            if (y <= 4) {
                if (x > 0)
                    out = out.with(square(x - 1, y));
                if (x < 8)
                    out = out.with(square(x + 1, y));
            }
            return out;
        }

        static BitBoard ourPawnSources(int x, int y) {
            BitBoard out = BitBoard.EMPTY;
            if (y > 0)
                out = out.with(square(x, y - 1));
            if (y >= 5) {
                if (x > 0)
                    out = out.with(square(x - 1, y));
                if (x < 8)
                    out = out.with(square(x + 1, y));
            }
            return out;
        }

        static boolean inside(int x, int y) {
            return x >= 0 && x < FILES && y >= 0 && y < RANKS;
        }

        record Jump(int to, int leg) {
        }
    }
}
