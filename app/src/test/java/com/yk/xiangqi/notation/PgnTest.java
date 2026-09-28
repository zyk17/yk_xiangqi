package com.yk.xiangqi.notation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.yk.xiangqi.core.GameState;
import com.yk.xiangqi.core.Move;
import com.yk.xiangqi.core.Position;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class PgnTest {
    @Test
    public void parsesIccsMovesTagsAndComments() {
        String text = "[Event \"测试\\\"棋局\"]\n[Format \"ICCS\"]\n[Result \"*\"]\n\n"
            + "1. G3-G4 {红方注释} h7-g7 {黑方注释}\n2. B2-E2 c9-e7 *\n";

        PgnGame pgn = Pgn.parse(text);

        assertEquals("测试\"棋局", pgn.tags().get("Event"));
        assertEquals("ICCS", pgn.tags().get("Format"));
        assertEquals("*", pgn.result());
        assertEquals(Arrays.asList("红方注释", "黑方注释", null, null), pgn.comments());
        assertEquals(List.of("g3g4", "h7g7", "b2e2", "c9e7"), pgn.game().moves().stream().map(Move::uci).toList());
        assertEquals(4, Pgn.parse(Pgn.write(pgn)).game().cursor());
    }

    @Test
    public void writesCustomInitialPositionAndRoundTripsComment() {
        GameState game = new GameState(Position.fromFen("4k4/9/9/9/9/9/9/9/4R4/4K4 w"));
        game = game.reduce(new com.yk.xiangqi.core.GameAction.Play(Move.parse("e1e2")));
        PgnGame pgn = new PgnGame(game, Map.of("Event", "残局"), Map.of(0, "将死"), "1-0");

        String text = Pgn.write(pgn);
        PgnGame restored = Pgn.parse(text);

        assertTrue(text.contains("[SetUp \"1\"]"));
        assertTrue(text.contains("[FEN \"4k4/9/9/9/9/9/9/9/4R4/4K4 w - - 0 1\"]"));
        assertEquals("e1e2", restored.game().moves().get(0).uci());
        assertEquals("将死", restored.comments().get(0));
        assertEquals("1-0", restored.result());
    }

    @Test
    public void commentsAreOptionalAndAddressedByPly() {
        GameState game = new GameState();
        game = game.reduce(new com.yk.xiangqi.core.GameAction.Play(Move.parse("g3g4")));
        game = game.reduce(new com.yk.xiangqi.core.GameAction.Play(Move.parse("h7g7")));
        PgnGame pgn = new PgnGame(game, Map.of(), Map.of(1, "只注释黑方"), "*");

        assertEquals(Arrays.asList(null, "只注释黑方"), pgn.comments());
        assertTrue(Pgn.write(pgn).contains("H7-G7 {只注释黑方}"));
    }

    @Test
    public void acceptsStandardFenWithoutSetupButRequiresSetupForCustomFen() {
        PgnGame standard = Pgn.parse("[FEN \"" + Position.START_FEN + "\"]\n\n*");
        assertEquals(Position.START_FEN, standard.game().initialPosition().toFen());

        try {
            Pgn.parse("[FEN \"4k4/9/9/9/9/9/9/9/4R4/4K4 w\"]\n\n*");
            throw new AssertionError("自定义 FEN 未被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("SetUp"));
        }
    }

    @Test
    public void readsGb18030AndWritesUtf8OrRequestedCharset() throws Exception {
        String text = "[Event \"中文\"]\n\n1. G3-G4 {注释} *\n";
        byte[] gb18030 = text.getBytes(Charset.forName("GB18030"));
        PgnGame game = PgnIo.read(new ByteArrayInputStream(gb18030));
        assertEquals("中文", game.tags().get("Event"));
        assertEquals("注释", game.comments().get(0));
        assertEquals(game.game().cursor(), PgnIo.parse(PgnIo.write(game)).game().cursor());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PgnIo.write(game, output, Charset.forName("GB18030"));
        assertEquals("中文", PgnIo.parse(output.toByteArray()).tags().get("Event"));

        try {
            PgnIo.write(game, new ByteArrayOutputStream(), StandardCharsets.US_ASCII);
            throw new AssertionError("不可编码字符未被拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("US-ASCII"));
        }

        byte[] bom = new byte[PgnIo.write(game).length + 3];
        bom[0] = (byte) 0xef;
        bom[1] = (byte) 0xbb;
        bom[2] = (byte) 0xbf;
        System.arraycopy(PgnIo.write(game), 0, bom, 3, bom.length - 3);
        assertEquals("中文", PgnIo.parse(bom).tags().get("Event"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsIllegalMove() {
        Pgn.parse("1. A0-I9 *");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnclosedComment() {
        Pgn.parse("1. G3-G4 {未结束");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsCommentAfterResult() {
        Pgn.parse("1. G3-G4 1-0 {终局说明}");
    }
}
