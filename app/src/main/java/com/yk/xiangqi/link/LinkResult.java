package com.yk.xiangqi.link;

/** 连线状态归约后的下一状态与应用动作。 */
public record LinkResult(LinkState state, LinkAction action) {}
