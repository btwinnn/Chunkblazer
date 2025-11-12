const fs = require('fs');
const path = require('path');
const readline = require('readline');

// --- CONFIGURATION: Add your files here ---
// We use 'Tasks_JSON/' because the script is now in the root of Chunkblazer
const fileList = [
    'Tasks_JSON/Starter_Area_Tasks.json',
    'Tasks_JSON/Misthalin_Tasks.json',
    'Tasks_JSON/Novice_Mystery_Tasks.json' 
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
    
    // Split the "Has" input into individual words
    const searchKeywords = filters.has ? filters.has.toLowerCase().split(" ") : [];

    chunks.forEach((chunk) => {
        if (chunk.tasks) {
            const matchingTasks = chunk.tasks.filter((task) => {
                
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

                return categoryMatch && typeMatch && hasMatch;
            });

            matchingTasks.forEach((task) => {
                results.push({
                    found_in_region: chunk.friendly_name || chunk.Friendly_Name, 
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

// PROMPT 1: KEYWORD (Now First!)
rl.question('Keyword? (e.g. "Goblin", "Mugger seconds") [Enter to skip]: ', (hasInput) => {
    
    // PROMPT 2: CATEGORY
    rl.question('Category? (e.g. "Combat") [Enter to skip]: ', (catInput) => {
        
        // PROMPT 3: TYPE
        rl.question('Type? (e.g. "Kill") [Enter to skip]: ', (typeInput) => {
        
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
                console.log(JSON.stringify(matches, null, 2)); 
            } else {
                console.log("No matches found.");
            }

            rl.close();
        });
    });
});