class_name Cards
## Карта кодируется целым числом: suit * 16 + rank.
## Масти: 0 ♠ пики, 1 ♣ трефы, 2 ♦ бубны, 3 ♥ червы. Ранги: 2..14 (14 — туз).

const SUIT_SYMBOLS := ["♠", "♣", "♦", "♥"]
const SUIT_NAMES := ["пики", "трефы", "бубны", "червы"]
const NO_CARD := -1


static func make(suit: int, rank: int) -> int:
	return suit * 16 + rank


static func suit(c: int) -> int:
	return c >> 4


static func rank(c: int) -> int:
	return c & 15


static func is_red(c: int) -> bool:
	return suit(c) >= 2


static func min_rank_for_deck(size: int) -> int:
	match size:
		24:
			return 9
		52:
			return 2
		_:
			return 6


static func build_deck(size: int) -> Array[int]:
	var deck: Array[int] = []
	for s in 4:
		for r in range(min_rank_for_deck(size), 15):
			deck.append(make(s, r))
	return deck


## Бьёт ли карта d карту a при козырной масти trump.
static func beats(a: int, d: int, trump: int) -> bool:
	if suit(a) == suit(d):
		return rank(d) > rank(a)
	return suit(d) == trump and suit(a) != trump


static func rank_text(c: int) -> String:
	var r := rank(c)
	match r:
		11:
			return "В"
		12:
			return "Д"
		13:
			return "К"
		14:
			return "Т"
	return str(r)


static func to_text(c: int) -> String:
	return rank_text(c) + SUIT_SYMBOLS[suit(c)]


## Условная «ценность» карты: козыри дороже любых некозырей.
static func value(c: int, trump: int) -> int:
	return rank(c) + (13 if suit(c) == trump else 0)


static func sort_hand(hand: Array, trump: int) -> Array:
	var h := hand.duplicate()
	h.sort_custom(func(a, b):
		var ta := suit(a) == trump
		var tb := suit(b) == trump
		if ta != tb:
			return tb  # козыри справа
		if suit(a) != suit(b):
			return suit(a) < suit(b)
		return rank(a) < rank(b))
	return h
