const fs = require('fs');
const path = require('path');
const readline = require('readline');

// --- CONFIGURATION: Add your files here ---
<<<<<<< HEAD
const fileList = [
    'Starter_Area_Tasks.json',
    'Misthalin_Tasks.json' 
=======
// We use 'Tasks_JSON/' because the script is now in the root of Chunkblazer
const fileList = [
    'Tasks_JSON/Starter_Area_Tasks.json',
    'Tasks_JSON/Misthalin_Tasks.json',
    'Tasks_JSON/Novice_Mystery_Tasks.json' 
>>>>>>> c77e3046d420c0d385a35192bf29ca7e63a68365
];

// 1. Load and Combine Data from ALL files
let allChunks = [];

console.log("--- Loading Data ---");

fileList.forEach(fileName => {
    const fullPath = path.join(__dirname, fileName);
    
    try {
        if (fs.existsSync(fullPath)) {
            const rawData = fs.readFileSync(fullPath, 'utf8');
            const jsonData = JSON.parse(rawData);

            // Dynamically find lists inside the files
            Object.values(jsonData).forEach(val => {
                if (Array.isArray(val)) {
                    allChunks = allChunks.concat(val);
                }
            });
            console.log(`Loaded: ${fileName}`);
        } else {
            console.warn(`Warning: Could not find file: ${fileName}`);
        }
    } catch (err) {
        console.error(`Error loading ${fileName}:`, err.message);
    }
});

console.log(`Total chunks loaded: ${allChunks.length}`);


// 2. The Search Function
function searchTasks(chunks, filters) {
    const results = [];

    // Normalize search terms
    const searchCategory = filters.category ? filters.category.toLowerCase() : "";
    const searchType = filters.completion_type ? filters.completion_type.toLowerCase() : "";
<<<<<<< HEAD
    const searchKeyword = filters.has ? filters.has.toLowerCase() : "";
=======
    
    // Split the "Has" input into individual words
    const searchKeywords = filters.has ? filters.has.toLowerCase().split(" ") : [];
>>>>>>> c77e3046d420c0d385a35192bf29ca7e63a68365

    chunks.forEach((chunk) => {
        if (chunk.tasks) {
            const matchingTasks = chunk.tasks.filter((task) => {
                
<<<<<<< HEAD
                // 1. Category Check
                const taskCat = task.category ? task.category.toLowerCase() : "";
                const categoryMatch = !searchCategory || taskCat.includes(searchCategory);

                // 2. Completion Type Check
                const taskType = task.completion_type ? task.completion_type.toLowerCase() : "";
                const typeMatch = !searchType || taskType.includes(searchType);

                // 3. General "Has" Keyword Check
                const taskName = task.name ? task.name.toLowerCase() : "";
                const taskId = task.taskID ? task.taskID.toLowerCase() : "";
                
                const hasMatch = !searchKeyword || (
                    taskName.includes(searchKeyword) || 
                    taskId.includes(searchKeyword) || 
                    taskCat.includes(searchKeyword) || 
                    taskType.includes(searchKeyword)
                );
=======
                // Category & Type Check
                const taskCat = task.category ? task.category.toLowerCase() : "";
                const categoryMatch = !searchCategory || taskCat.includes(searchCategory);

                const taskType = task.completion_type ? task.completion_type.toLowerCase() : "";
                const typeMatch = !searchType || taskType.includes(searchType);

                // Keyword Check (Multi-word support)
                const fullTaskText = (
                    (task.name || "") + " " + 
                    (task.taskID || "") + " " + 
                    (taskCat || "") + " " + 
                    (taskType || "")
                ).toLowerCase();

                const hasMatch = searchKeywords.length === 0 || searchKeywords.every(word => fullTaskText.includes(word));
>>>>>>> c77e3046d420c0d385a35192bf29ca7e63a68365

                return categoryMatch && typeMatch && hasMatch;
            });

            matchingTasks.forEach((task) => {
                results.push({
<<<<<<< HEAD
                    // We put the Region Name first so you know where it is
                    found_in_region: chunk.friendly_name || chunk.Friendly_Name, 
                    
                    // THE FIX: This "..." grabs the ENTIRE task object (constraints, target_npc, etc.)
=======
                    found_in_region: chunk.friendly_name || chunk.Friendly_Name, 
>>>>>>> c77e3046d420c0d385a35192bf29ca7e63a68365
                    ...task 
                });
            });
        }
    });
    return results;
}

// 3. SET UP INTERACTIVE INPUT
const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout
});

console.log("\n--- OSRS Task Search (Full Details) ---");

<<<<<<< HEAD
rl.question('Category? (e.g. "Combat") [Enter to skip]: ', (catInput) => {
    rl.question('Type? (e.g. "Kill") [Enter to skip]: ', (typeInput) => {
        rl.question('Keyword? (e.g. "Goblin") [Enter to skip]: ', (hasInput) => {
=======
// PROMPT 1: KEYWORD (Now First!)
rl.question('Keyword? (e.g. "Goblin", "Mugger seconds") [Enter to skip]: ', (hasInput) => {
    
    // PROMPT 2: CATEGORY
    rl.question('Category? (e.g. "Combat") [Enter to skip]: ', (catInput) => {
        
        // PROMPT 3: TYPE
        rl.question('Type? (e.g. "NPC_Kill") [Enter to skip]: ', (typeInput) => {
>>>>>>> c77e3046d420c0d385a35192bf29ca7e63a68365
        
            const myFilters = {
                category: catInput.trim(),
                completion_type: typeInput.trim(),
                has: hasInput.trim()
            };

            console.log("\n---------------------------------------------------");
            console.log(`Searching...`);
            console.log("---------------------------------------------------");

            const matches = searchTasks(allChunks, myFilters);

            if (matches.length > 0) {
                console.log(`Found ${matches.length} matches:`);
<<<<<<< HEAD
                // This prints the full object structure nicely
=======
>>>>>>> c77e3046d420c0d385a35192bf29ca7e63a68365
                console.log(JSON.stringify(matches, null, 2)); 
            } else {
                console.log("No matches found.");
            }

            rl.close();
        });
    });
});