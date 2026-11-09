/*
 * AIImageDetect4Autopsy
 * Copyright (C) 2014 Rajmund Witt (SmutDetect4Autopsy, from which the module
 * structure of this project is derived)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package uk.co.aiimagedetect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metadata indicator rules, ported from the Python checker's
 * PROVIDER_PATTERNS / AI_TERMS / generation-field list and scoring.
 *
 * Differences from the Python version (all to cut false positives):
 *  - bare "google", "adobe.com", "nvidia", "canva", "runway" and "flux" no
 *    longer count. They match ordinary photos (Make=Google on Pixel phones,
 *    the Adobe namespace URL inside every XMP packet, fashion "runway" shots).
 *  - "digital source type" alone no longer counts, since cameras write
 *    digitalCapture there. Only the AI values (trainedAlgorithmicMedia etc.)
 *    count.
 *  - "parameters", "prompt", "workflow" ... are matched against metadata KEYS
 *    (PNG text keywords), not as substrings of all text.
 */
final class AiIndicatorRules {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE;

    private static final Map<String, List<Pattern>> PROVIDERS = new LinkedHashMap<>();
    private static final Set<String> MEDIUM_PROVIDERS = new HashSet<>(
            Arrays.asList("Stable Diffusion ecosystem", "ComfyUI", "NovelAI"));

    private static final Pattern AI_TERMS = Pattern.compile(
            "trainedalgorithmicmedia|(?<![a-z])algorithmicmedia|generative.?ai|\\bai[\\s_-]?generated"
            + "|text.?to.?image|image.?generation", FLAGS);

    private static final Set<String> GENERATION_KEYS = new HashSet<>(Arrays.asList(
            "parameters", "workflow", "prompt", "generation_data", "sd-metadata"));

    private static final Map<String, String> GENERATION_KEY_LABELS = new LinkedHashMap<>();

    static {
        provider("OpenAI / DALL-E / ChatGPT", "\\bdall[\\s_-]?e\\b", "\\bopenai\\b", "\\bgpt[\\s_-]?image\\b", "chatgpt");
        provider("Google Gemini / Imagen", "\\bgemini\\b", "\\bimagen\\b", "\\bsynthid\\b", "google\\.deepmind",
                "made with google ai");
        provider("Midjourney", "\\bmidjourney\\b");
        provider("Adobe Firefly", "\\badobe\\s*firefly\\b", "\\bfirefly\\b");
        provider("Stable Diffusion ecosystem", "\\bstable\\s*diffusion\\b", "\\bstablediffusion\\b",
                "\\bautomatic1111\\b", "\\ba1111\\b", "\\bwebui\\b", "\\bnegative prompt\\b", "\\bsteps:\\s*\\d+",
                "\\bcfg scale\\b", "\\bsampler:\\s*");
        provider("ComfyUI", "\\bcomfyui\\b", "\"class_type\"\\s*:", "\"workflow\"\\s*:");
        provider("NovelAI", "\\bnovelai\\b");
        provider("FLUX", "\\bflux\\.1\\b", "\\bflux[\\s_-](?:schnell|dev|pro)\\b", "\\bblack forest labs\\b");
        provider("Leonardo AI", "\\bleonardo\\.ai\\b", "\\bleonardo ai\\b");
        provider("Ideogram", "\\bideogram\\b");
        provider("Runway", "\\brunwayml\\b", "\\brunway\\s*gen");
        provider("Microsoft Designer / Image Creator", "\\bmicrosoft designer\\b", "\\bbing image creator\\b",
                "\\bdall[\\s_-]?e[\\s_-]?3\\b");
        provider("NVIDIA", "\\bnvidia\\s+(?:picasso|edify)\\b");
        provider("Canva AI", "\\bcanva\\s+ai\\b", "\\bmagic media\\b");

        GENERATION_KEY_LABELS.put("parameters", "Stable Diffusion generation parameters");
        GENERATION_KEY_LABELS.put("workflow", "ComfyUI workflow");
        GENERATION_KEY_LABELS.put("prompt", "generation prompt");
        GENERATION_KEY_LABELS.put("generation_data", "generation data");
        GENERATION_KEY_LABELS.put("sd-metadata", "InvokeAI (legacy) metadata");
        GENERATION_KEY_LABELS.put("invokeai", "InvokeAI metadata");
    }

    private AiIndicatorRules() {
    }

    private static void provider(String name, String... regexes) {
        List<Pattern> list = new ArrayList<>();
        for (String r : regexes) {
            list.add(Pattern.compile(r, FLAGS));
        }
        PROVIDERS.put(name, list);
    }

    static void collect(ExtractedMetadata md, List<Finding> findings, SortedSet<String> providers) {
        final String text = md.searchText();

        for (Map.Entry<String, List<Pattern>> e : PROVIDERS.entrySet()) {
            List<String> hits = new ArrayList<>();
            for (Pattern p : e.getValue()) {
                Matcher m = p.matcher(text);
                if (m.find()) {
                    hits.add("'" + clip(m.group(), 40) + "'");
                }
            }
            if (!hits.isEmpty()) {
                providers.add(e.getKey());
                findings.add(new Finding("provider_metadata_clue",
                        MEDIUM_PROVIDERS.contains(e.getKey()) ? "medium" : "low_to_medium",
                        "Generator text for " + e.getKey() + " in metadata, matched " + join(hits, 3)
                        + " (metadata can be edited or copied)", 15));
            }
        }

        Matcher ai = AI_TERMS.matcher(text);
        if (ai.find()) {
            findings.add(new Finding("AI_provenance_metadata", "medium",
                    "AI-generation / trained-algorithmic-media marker in metadata: '" + clip(ai.group(), 40) + "'", 25));
        }

        Set<String> seenKeys = new HashSet<>();
        for (MetaField f : md.fields) {
            String key = f.key.toLowerCase(Locale.ROOT);
            String label = null;
            if (GENERATION_KEYS.contains(key)) {
                label = GENERATION_KEY_LABELS.get(key);
            } else if (key.startsWith("invokeai")) {
                label = GENERATION_KEY_LABELS.get("invokeai");
                key = "invokeai";
            }
            if (label != null && seenKeys.add(key)) {
                findings.add(new Finding("generation_metadata_field", "medium",
                        "Metadata field '" + f.key + "' present (" + label + ") in " + f.source, 20));
            }
        }

        if (md.c2paPresent) {
            findings.add(new Finding("c2pa_manifest_present", "requires_validation_review",
                    "C2PA manifest present. Signature NOT validated here; review it with a C2PA validator before "
                    + "attributing origin. Cameras and editors also write C2PA, so presence alone is not evidence of AI.", 10));
        } else if (md.possibleC2paMarker) {
            findings.add(new Finding("possible_c2pa_marker", "low",
                    "A C2PA-related byte marker exists, but no manifest was parsed.", 5));
        }
    }

    private static String clip(String s, int max) {
        String t = s.replace('\n', ' ').replace('\r', ' ');
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    private static String join(List<String> items, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size() && i < max; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(items.get(i));
        }
        if (items.size() > max) {
            sb.append(" +").append(items.size() - max).append(" more");
        }
        return sb.toString();
    }
}
