package com.chaekcheck.aisearch.internal.flow;

import java.util.ArrayList;
import java.util.List;

import com.chaekcheck.aisearch.internal.llm.LlmClient.Message;
import com.chaekcheck.aisearch.internal.llm.LlmClient.Role;

/**
 * The two calls' instructions and JSON schemas (cycle 24 step 1, measured with {@code scripts/aisearch/prototype_haiku.py}
 * on 2026-10-24; dev docs/plan-feedback.md {@code # 사이클 24} "1단계"). The second call's wording keeps the word 후보 out
 * of the answers (user 2026-10-24 "그래"). No {@code maxItems}: structured outputs refuse it (400), the server keeps at
 * most 8 picks itself. What is sent: the question, the earlier questions with the titles shown then, and the books'
 * number, title, author and search line; never a member number, an IP or a library name (spec R4-4).
 */
final class Prompts {

	static final String KEYWORD_SYSTEM = """
			너는 도서관 책 찾기 도우미의 첫 단계다. 사용자가 쓴 글을 읽고 아래를 JSON으로만 답한다.
			- notBook: 책을 찾는 요청이 아니면 true (예: 날씨, 식사, 잡담). 책을 찾는 말이면 false.
			- titleLike: 글이 책 제목이나 저자 이름 그 자체로 보이면 true (예: "채식주의자", "한강"). 설명이나 조건을 적은 문장이면 false.
			- title: titleLike가 true일 때 그 제목(또는 저자)을 사용자가 쓴 글에 있는 글자 그대로 적는다. 아니면 빈 문자열.
			- refersToShown: 앞 대화에서 보여 준 책을 가리키는 말이면 true (예: "이 중에 소설만", "더 짧은 걸로", "두 번째 책 비슷한 것"). 아니면 false.
			- keywords: 책 제목·저자·소개 한 줄에서 찾을 한국어 낱말 1~8개. 명사나 짧은 명사구로, 조사 없이. 주제(과학, 경제), 장르(소설, 에세이, 추리), 분위기(위로, 웃긴), 읽는 사람(아이, 입문)처럼 책 소개에 적힐 법한 말을 고른다. 같은 뜻의 다른 표현도 넣는다(예: "SF", "과학소설"). notBook이면 빈 배열.
			- 앞 대화가 있으면 이번 글을 그 맥락에서 읽되, keywords는 이번 글의 뜻으로 고른다.""";

	static final String KEYWORD_SCHEMA = """
			{"type":"object","properties":{"notBook":{"type":"boolean"},"titleLike":{"type":"boolean"},
			"title":{"type":"string"},"refersToShown":{"type":"boolean"},"keywords":{"type":"array","items":{"type":"string"}}},
			"required":["notBook","titleLike","title","refersToShown","keywords"],"additionalProperties":false}""";

	static final String PICK_SYSTEM = """
			너는 도서 추천 도우미다.
			- 반드시 아래 책들의 번호 중에서만 고른다. 아래에 없는 책을 말하지 않는다.
			- 질문에 맞는 책을 0~8권 고른다. 맞는 책이 없으면 빈 목록을 돌려준다. 억지로 고르지 않는다.
			- 맨 앞 head에 질문에 답하는 머리말을 한국어 한 문장으로 쓴다. 고른 책이 없으면 맞는 책이 적다고 쓴다.
			- 고른 책마다 질문에 맞춘 글을 한국어 2~3문장으로 쓴다. 왜 이 질문에 맞는지가 드러나게 쓴다.
			- 아래 책에 적힌 내용(제목, 저자, 한 줄)만 근거로 쓴다. 적혀 있지 않은 줄거리·수상·저자 정보를 지어내지 않는다.
			- 한 줄의 문장을 그대로 옮기지 않는다. 자기 말로 다시 쓴다.
			- head와 글은 사용자에게 그대로 보인다. "후보", "목록", 책 번호를 쓰지 않는다. 맞는 책이 없을 때도 "찾으시는 책"처럼 사용자 쪽에서 말한다.
			- 앞 대화가 있으면 이어서 묻는 말로 읽는다. 앞 대화에서 보여 준 책을 가리키는 질문이면 그 책들 가운데서 고른다.
			- 정해진 JSON 형식으로만 답한다.""";

	static final String PICK_SCHEMA = """
			{"type":"object","properties":{"head":{"type":"string"},"picks":{"type":"array","items":{"type":"object",
			"properties":{"no":{"type":"integer"},"text":{"type":"string"}},"required":["no","text"],
			"additionalProperties":false}}},"required":["head","picks"],"additionalProperties":false}""";

	private Prompts() {
	}

	/** An earlier turn as the model sees it: the question, then the titles shown (spec R11-9). */
	record ShownTurn(String question, List<String> titles) {
	}

	/** A book as the second call sees it. */
	record Choice(String title, String author, String line) {
	}

	static List<Message> keywordMessages(List<ShownTurn> history, String question) {
		List<Message> messages = history(history);
		messages.add(new Message(Role.USER, question));
		return messages;
	}

	static List<Message> pickMessages(List<ShownTurn> history, String question, List<Choice> choices) {
		StringBuilder user = new StringBuilder("질문: ").append(question).append("\n고를 수 있는 책:");
		for (int i = 0; i < choices.size(); i++) {
			Choice choice = choices.get(i);
			user.append('\n')
				.append(i + 1)
				.append(". 제목: ")
				.append(choice.title())
				.append(" / 저자: ")
				.append(choice.author() == null || choice.author().isBlank() ? "(모름)" : choice.author());
			if (choice.line() != null && !choice.line().isBlank()) {
				user.append(" / 한 줄: ").append(choice.line());
			}
		}
		List<Message> messages = history(history);
		messages.add(new Message(Role.USER, user.toString()));
		return messages;
	}

	private static List<Message> history(List<ShownTurn> history) {
		List<Message> messages = new ArrayList<>();
		for (ShownTurn turn : history) {
			messages.add(new Message(Role.USER, turn.question()));
			StringBuilder shown = new StringBuilder("보여 준 책:");
			if (turn.titles().isEmpty()) {
				shown.append("\n(없음)");
			}
			for (String title : turn.titles()) {
				shown.append("\n- ").append(title);
			}
			messages.add(new Message(Role.ASSISTANT, shown.toString()));
		}
		return messages;
	}

}
