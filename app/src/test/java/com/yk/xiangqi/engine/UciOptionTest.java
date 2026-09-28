package com.yk.xiangqi.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertNull;

import java.util.List;
import org.junit.Test;

public final class UciOptionTest {
    @Test
    public void parsesNameTypeAndDefaultValue() {
        UciOption option = UciOption.parse("option name Clear Hash type button");
        assertEquals("Clear Hash", option.name);
        assertEquals("button", option.type);
        assertNull(option.defaultValue);

        option = UciOption.parse("option name Skill Level type spin default 20 min 0 max 20");
        assertEquals("Skill Level", option.name);
        assertEquals("spin", option.type);
        assertEquals("20", option.defaultValue);
        assertEquals(Long.valueOf(0), option.min);
        assertEquals(Long.valueOf(20), option.max);

        option = UciOption.parse("option name Style type combo default Normal var Solid var Normal");
        assertEquals("Style", option.name);
        assertEquals("combo", option.type);
        assertEquals("Normal", option.defaultValue);
        assertEquals(List.of("Solid", "Normal"), option.vars);
    }

    @Test
    public void formatsAndValidatesSetOption() {
        assertEquals("setoption name Threads value 1", UciEngine.setOption("Threads", "1"));
        assertEquals("setoption name Clear Hash", UciOption.parse("option name Clear Hash type button").setOption(null));
        assertEquals("setoption name Ponder value true", UciOption.parse("option name Ponder type check default false").setOption("true"));
        assertEquals("setoption name Skill Level value 10",
            UciOption.parse("option name Skill Level type spin default 20 min 0 max 20").setOption("10"));
        assertEquals("setoption name Style value Solid",
            UciOption.parse("option name Style type combo default Normal var Solid var Normal").setOption("Solid"));
    }

    @Test
    public void rejectsInvalidTypedValues() {
        try {
            UciOption.parse("option name Skill Level type spin default 20 min 0 max 20").setOption("21");
            fail("应拒绝超出范围的 spin 值");
        } catch (IllegalArgumentException expected) {
        }
        try {
            UciOption.parse("option name Style type combo default Normal var Solid var Normal").setOption("Fast");
            fail("应拒绝未声明的 combo 值");
        } catch (IllegalArgumentException expected) {
        }
    }
}
