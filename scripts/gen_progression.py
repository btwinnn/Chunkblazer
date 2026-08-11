"""Generate Progression_Tasks.json — the Progression tier of the Global Task pool.

24 skills x 10 level thresholds. Written to both the plugin's shipped resources
and the server's go:embed catalog from one source, because those two copies
drifting is a known failure mode (see cmd/catalogdrift): a task missing from the
server catalog scores ZERO server-side on every sync.

Hitpoints level 10 is deliberately omitted — accounts start at 10 HP, so it
would be unearnable by construction. Every other level-1 start (Attack, Magic,
...) has its level-10 rung intact.
"""
import json

# base_points by threshold, per the design ladder (28 points per skill).
LADDER = [
    (10, 1), (20, 1), (30, 2), (40, 2), (50, 2),
    (60, 3), (70, 3), (80, 4), (90, 5), (99, 5),
]

# net.runelite.api.Skill constant names. OVERALL is not a real skill.
SKILLS = [
    "ATTACK", "STRENGTH", "DEFENCE", "HITPOINTS", "RANGED", "PRAYER", "MAGIC",
    "COOKING", "WOODCUTTING", "FLETCHING", "FISHING", "FIREMAKING", "CRAFTING",
    "SMITHING", "MINING", "HERBLORE", "AGILITY", "THIEVING", "SLAYER",
    "FARMING", "RUNECRAFT", "HUNTER", "CONSTRUCTION", "SAILING",
]

# Display names where the enum constant isn't the in-game skill name.
DISPLAY = {"RUNECRAFT": "Runecrafting"}

# Accounts spawn with 10 Hitpoints, so that rung can never be earned.
SKIPPED = {("HITPOINTS", 10)}


def display_name(skill):
    return DISPLAY.get(skill, skill.capitalize())


tasks = []
for skill in SKILLS:
    for level, points in LADDER:
        if (skill, level) in SKIPPED:
            continue
        pretty = display_name(skill)
        tasks.append({
            "name": f"Reach Level {level} {pretty}",
            "taskID": f"progression_{skill.lower()}_{level}",
            "category": "Progression",
            "completion_type": "SKILL_THRESHOLD",
            "base_points": points,
            "level": level,
            "constraints": {
                "required_skill": skill,
                "required_level": level,
            },
        })

doc = {
    "Progression_Tasks": [
        {
            "region_id": [],
            "Friendly_Name": "Progression",
            "tasks": tasks,
        }
    ]
}

OUT = [
    r"C:\Chunkblazer\src\main\resources\com\chunkblazer\Progression_Tasks.json",
    r"C:\ChunkBlazer-Server\internal\tasks\data\Progression_Tasks.json",
    r"C:\Chunkblazer\Tasks_JSON\All_Areas_Task_Folder\Progression_Tasks.json",
]
for path in OUT:
    with open(path, "w", encoding="utf-8") as f:
        json.dump(doc, f, indent=4, ensure_ascii=False)
        f.write("\n")
    print("wrote", path)

total = sum(t["base_points"] for t in tasks)
print(f"\n{len(tasks)} tasks across {len(SKILLS)} skills, {total} points total")
print(f"per-skill: {sum(p for _, p in LADDER)} (hitpoints: {sum(p for l, p in LADDER if l != 10)})")
