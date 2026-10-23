package dev.chatspellcheck.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Suggestion quality. The three typo lists were written in order: TUNING was used to design the
 * ranker, HELD_OUT was checked once, and FINAL was written after the weights were frozen.
 * Thresholds guard against regressions; the printed numbers are the actual measurement.
 */
class RankingTest {
	private static final HunspellChecker CHECKER = TestResources.enUs();
	private static final SuggestionRanker RANKER = new SuggestionRanker(TestResources.frequency());

	static final String[][] TUNING = {
		{"teh","the"},{"recieve","receive"},{"definately","definitely"},{"seperate","separate"},{"wierd","weird"},
		{"becuase","because"},{"freind","friend"},{"untill","until"},{"tommorow","tomorrow"},{"occured","occurred"},
		{"accomodate","accommodate"},{"beleive","believe"},{"goverment","government"},{"enviroment","environment"},
		{"wich","which"},{"thier","their"},{"realy","really"},{"probaly","probably"},{"diffrent","different"},
		{"intresting","interesting"},{"begining","beginning"},{"neccessary","necessary"},{"existance","existence"},
		{"arguement","argument"},{"finaly","finally"},{"happend","happened"},{"knowlege","knowledge"},{"libary","library"},
		{"noticable","noticeable"},{"prefered","preferred"},{"recomend","recommend"},{"rythm","rhythm"},
		{"succesful","successful"},{"suprise","surprise"},{"truely","truly"},{"wether","whether"},{"writting","writing"},
		{"acheive","achieve"},{"adress","address"},{"basicly","basically"},{"buisness","business"},{"comming","coming"},
		{"completly","completely"},{"dissapear","disappear"},{"dont","don't"},{"im","I'm"},{"doesnt","doesn't"},
		{"didnt","didn't"},{"thats","that's"},{"hwllo","hello"},{"wprld","world"},{"thw","the"},{"tge","the"},
		{"jsut","just"},{"abd","and"},{"knwo","know"},{"waht","what"},{"taht","that"},{"yuo","you"},{"ahve","have"},
		{"becasue","because"},{"somthing","something"},{"anyting","anything"},{"evryone","everyone"},{"plase","please"},
		{"helo","hello"},{"Teh","The"},{"Recieve","Receive"},{"alot","a lot"},
		{"diamons","diamonds"},{"swrod","sword"},{"villiage","village"}
	};
	// Written before any tuning; not used to adjust weights.
	static final String[][] HELD_OUT = {
		{"absense","absence"},{"agressive","aggressive"},{"apparantly","apparently"},{"cemetary","cemetery"},
		{"commited","committed"},{"concious","conscious"},{"curiousity","curiosity"},{"embarass","embarrass"},
		{"foriegn","foreign"},{"gaurd","guard"},{"grammer","grammar"},{"harrass","harass"},{"hieght","height"},
		{"immediatly","immediately"},{"independant","independent"},{"lisence","license"},{"maintenence","maintenance"},
		{"millenium","millennium"},{"mischevious","mischievous"},{"occurence","occurrence"},{"persistant","persistent"},
		{"posession","possession"},{"publically","publicly"},{"questionaire","questionnaire"},{"reccomend","recommend"},
		{"refered","referred"},{"relevent","relevant"},{"religous","religious"},{"remeber","remember"},
		{"resistence","resistance"},{"sieze","seize"},{"sentance","sentence"},{"tendancy","tendency"},
		{"threshhold","threshold"},{"tounge","tongue"},{"vaccuum","vacuum"},{"yeild","yield"},
		{"woth","with"},{"frim","from"},{"ehat","what"},{"nkw","now"},{"ypu","you"},{"thst","that"},{"gpod","good"},
		{"wirk","work"},{"hrre","here"},{"liek","like"},{"peopel","people"},{"wnat","want"},{"cna","can"},
		{"tiem","time"},{"bcause","because"},{"togther","together"},{"stil","still"},{"comeing","coming"},
		{"lookin","looking"},{"minning","mining"},{"crafing","crafting"},{"enchantmnet","enchantment"},
		{"potoin","potion"},{"zombe","zombie"},{"skeliton","skeleton"},{"spidr","spider"},{"chickn","chicken"},
		{"ffirend","friend"},{"isnt","isn't"},{"youre","you're"},{"cant","can't"}
	};

	// Written after tuning was frozen; never used to adjust anything.
	static final String[][] FINAL = {
		{"alredy","already"},{"tomorow","tomorrow"},{"thnks","thanks"},{"pleese","please"},{"whta","what"},{"agian","again"},
		{"anymroe","anymore"},{"bulding","building"},{"defenitely","definitely"},{"exellent","excellent"},{"gaurantee","guarantee"},
		{"hapy","happy"},{"interupt","interrupt"},{"jelous","jealous"},{"lenght","length"},{"mabye","maybe"},{"nieghbor","neighbor"},
		{"oppurtunity","opportunity"},{"perhapes","perhaps"},{"quik","quick"},{"reallly","really"},{"shoudl","should"},
		{"strenght","strength"},{"usualy","usually"},{"vilage","village"},{"wiht","with"},{"yesturday","yesterday"},
		{"sumthing","something"},{"evrything","everything"},{"realyl","really"},{"haev","have"},{"waiitng","waiting"},
		{"insted","instead"},{"beacuse","because"},{"diffcult","difficult"},{"finsih","finish"},{"gettin","getting"},
		{"bascially","basically"},{"probly","probably"},{"tring","trying"},{"wuold","would"},{"cuold","could"},{"shoud","should"},
		{"knwon","known"},{"thoguht","thought"},{"freinds","friends"},{"togehter","together"},{"wierd","weird"},{"kinda","kinda"},{"ofc","ofc"}
	};

	static List<String> suggest(SuggestionRanker ranker, String word) {
		String query = SuggestionRanker.Casing.of(word) == SuggestionRanker.Casing.CAPITALIZED ? word.toLowerCase() : word;
		return ranker.rank(word, CHECKER.suggest(query));
	}

	/** @return {top-1 hits, top-3 hits, words the dictionary flagged} */
	private static int[] score(String name, String[][] cases) {
		return score(name, cases, RANKER);
	}

	static int[] score(String name, String[][] cases, SuggestionRanker ranker) {
		int n = 0, top1 = 0, top3 = 0;
		for (String[] c : cases) {
			if (CHECKER.isCorrect(c[0])) {
				continue;
			}
			n++;
			List<String> s = suggest(ranker, c[0]);
			if (!s.isEmpty() && s.get(0).equals(c[1])) top1++;
			if (s.subList(0, Math.min(3, s.size())).contains(c[1])) top3++;
		}
		System.out.printf("%s: top-1 %d/%d, top-3 %d/%d%n", name, top1, n, top3, n);
		return new int[] {top1, top3, n};
	}

	@Test
	void aggregateQualityDoesNotRegress() {
		int[] tuning = score("TUNING", TUNING);
		int[] heldOut = score("HELD_OUT", HELD_OUT);
		int[] fin = score("FINAL", FINAL);
		assertTrue(tuning[0] >= 66, "tuning top-1 " + tuning[0]);
		assertTrue(heldOut[0] >= 60, "held-out top-1 " + heldOut[0]);
		assertTrue(fin[0] >= 44, "final top-1 " + fin[0]);
		assertTrue(fin[1] >= 47, "final top-3 " + fin[1]);
	}

	@Test
	void commonTyposRankFirst() {
		assertEquals("the", suggest(RANKER, "teh").get(0));
		assertEquals("receive", suggest(RANKER, "recieve").get(0));
		assertEquals("which", suggest(RANKER, "wich").get(0));
		assertEquals("coming", suggest(RANKER, "comming").get(0));
		assertEquals("whether", suggest(RANKER, "wether").get(0));
		assertEquals("a lot", suggest(RANKER, "alot").get(0));
	}

	@Test
	void preservesCapitalisation() {
		assertEquals("The", suggest(RANKER, "Teh").get(0));
		assertEquals("Receive", suggest(RANKER, "Recieve").get(0));
	}

	@Test
	void offersContractions() {
		assertEquals("don't", suggest(RANKER, "dont").get(0));
		assertEquals("I'm", suggest(RANKER, "im").get(0));
		assertEquals("that's", suggest(RANKER, "thats").get(0));
	}

	@Test
	void dropsJunkSplits() {
		assertFalse(suggest(RANKER, "occured").contains("occur ed"));
		assertFalse(suggest(RANKER, "anyting").contains("any ting"));
		assertTrue(suggest(RANKER, "occured").size() <= SuggestionRanker.MAX_SUGGESTIONS);
	}

	@Test
	void minecraftVocabularyGetsABoost() {
		SuggestionRanker plain = RANKER;
		SuggestionRanker minecraft = new SuggestionRanker(TestResources.frequency(), Set.of("crafting")::contains);
		assertEquals("craving", suggest(plain, "crafing").get(0));
		assertEquals("crafting", suggest(minecraft, "crafing").get(0));
	}

	@Test
	void typoDistanceFavoursKeyboardNeighbours() {
		assertTrue(TypoDistance.adjacentKeys('q', 'w'));
		assertTrue(TypoDistance.adjacentKeys('a', 'q'));
		assertTrue(TypoDistance.adjacentKeys('h', 'n'));
		assertFalse(TypoDistance.adjacentKeys('q', 'p'));
		assertTrue(TypoDistance.between("wprld", "world") < TypoDistance.between("wzrld", "world"));
		assertTrue(TypoDistance.between("comming", "coming") < 1.0);
		assertTrue(TypoDistance.between("dont", "don't") < 0.5);
	}
}
