package com.pictionary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Built-in categorized word list used to offer drawers a choice of 3 words.
 * Hosts can additionally supply custom words per room (see {@link GameRoom}),
 * which are always included in the pool alongside whichever categories are enabled.
 */
public class WordBank {

    public static final String EASY = "easy";
    public static final String MEDIUM = "medium";
    public static final String HARD = "hard";

    public static final Set<String> ALL_CATEGORIES =
        Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(EASY, MEDIUM, HARD)));

    private static final String[] EASY_WORDS = {
        "apple", "banana", "sun", "moon", "star", "tree", "house", "car", "dog", "cat",
        "fish", "bird", "ball", "book", "chair", "table", "cup", "hat", "shoe", "cloud",
        "rain", "snow", "fire", "boat", "cake", "egg", "door", "key", "box", "flower",
        "grass", "bed", "phone", "clock", "spoon"
    };

    private static final String[] MEDIUM_WORDS = {
        "guitar", "umbrella", "bicycle", "robot", "dinosaur", "castle", "rainbow", "spider",
        "penguin", "rocket", "volcano", "skateboard", "telescope", "waterfall", "campfire",
        "jellyfish", "kangaroo", "lighthouse", "snowman", "octopus", "pumpkin", "dragon",
        "mermaid", "tornado", "avocado", "backpack", "balloon", "butterfly", "cactus",
        "compass", "dolphin", "firetruck", "hamburger", "igloo", "jigsaw"
    };

    private static final String[] HARD_WORDS = {
        "metamorphosis", "constellation", "procrastination", "ecosystem", "hieroglyphics",
        "kaleidoscope", "philosopher", "symphony", "architecture", "algorithm", "democracy",
        "hypothesis", "avalanche", "camouflage", "civilization", "gravity", "hologram",
        "immigration", "labyrinth", "meditation", "nostalgia", "parachute", "quarantine",
        "renaissance", "silhouette", "telepathy", "ultrasound", "vaccination", "wilderness",
        "xylophone", "yesteryear", "zeppelin", "gyroscope", "mausoleum", "palindrome"
    };

    private static final Random RNG = new Random();

    private WordBank() {}

    /**
     * Picks {@code count} distinct word options from the enabled categories plus any
     * custom words, preferring words not already used this game. Falls back to
     * reusing words (or padding by repetition) if the fresh pool is too small.
     */
    public static List<String> pickOptions(int count, Set<String> categories, List<String> customWords,
                                             Set<String> usedLowercase) {
        Set<String> cats = (categories == null || categories.isEmpty()) ? ALL_CATEGORIES : categories;

        List<String> pool = new ArrayList<>();
        if (customWords != null) pool.addAll(customWords);
        if (cats.contains(EASY)) pool.addAll(Arrays.asList(EASY_WORDS));
        if (cats.contains(MEDIUM)) pool.addAll(Arrays.asList(MEDIUM_WORDS));
        if (cats.contains(HARD)) pool.addAll(Arrays.asList(HARD_WORDS));

        // Dedupe while preserving order, then prefer words not yet used this game.
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(pool));
        List<String> fresh = new ArrayList<>();
        for (String w : distinct) {
            if (usedLowercase == null || !usedLowercase.contains(w.toLowerCase())) fresh.add(w);
        }
        List<String> source = fresh.size() >= count ? fresh : distinct;
        if (source.isEmpty()) return Collections.emptyList();

        List<String> shuffled = new ArrayList<>(source);
        Collections.shuffle(shuffled, RNG);

        List<String> result = new ArrayList<>();
        int i = 0;
        while (result.size() < count) {
            result.add(shuffled.get(i % shuffled.size()));
            i++;
            if (i > shuffled.size() * 2) break; // safety net for tiny pools
        }
        return result;
    }
}
