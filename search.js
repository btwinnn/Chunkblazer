const fs = require('fs');
const path = require('path');
const readline = require('readline');

// --- CONFIGURATION: Add your files here ---
const fileList = [
    'Starter_Area_Tasks.json',
    'Misthalin_Tasks.json' 
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
    const searchKeyword = filters.has ? filters.has.toLowerCase() : "";

    chunks.forEach((chunk) => {
        if (chunk.tasks) {
            const matchingTasks = chunk.tasks.filter((task) => {
                
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

                return categoryMatch && typeMatch && hasMatch;
            });

            matchingTasks.forEach((task) => {
                results.push({
                    // We put the Region Name first so you know where it is
                    found_in_region: chunk.friendly_name || chunk.Friendly_Name, 
                    
                    // THE FIX: This "..." grabs the ENTIRE task object (constraints, target_npc, etc.)
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

rl.question('Category? (e.g. "Combat") [Enter to skip]: ', (catInput) => {
    rl.question('Type? (e.g. "Kill") [Enter to skip]: ', (typeInput) => {
        rl.question('Keyword? (e.g. "Goblin") [Enter to skip]: ', (hasInput) => {
        
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
                // This prints the full object structure nicely
                console.log(JSON.stringify(matches, null, 2)); 
            } else {
                console.log("No matches found.");
            }

            rl.close();
        });
    });
});