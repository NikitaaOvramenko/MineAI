package io.github.nikitaaovramenko.mineai.tools;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;

public class DataRetrievalTool {
    private static final int MAX_RECIPES = 20;
    private static final int MAX_ALTERNATIVES_PER_INGREDIENT = 12;

    private final ToolContext context;

    public DataRetrievalTool(ToolContext context) {
        this.context = context;
    }

    @Tool("Returns items carried directly by the player.\n" + //
                "This does not include chests or other storage.\n" + //
                "When deciding whether the player owns enough materials to craft something,\n" + //
                "call findItemInStorage for each material that is still missing.")
    public List<String> getInventoryItems() {
        var inventory = context.player().getInventory();

        return IntStream.range(0, inventory.getContainerSize())
                .mapToObj(inventory::getItem)
                .filter(stack -> !stack.isEmpty())
                .map(stack -> stack.getCount() + "x "
                        + BuiltInRegistries.ITEM.getKey(stack.getItem()))
                .toList();
    }

    @Tool("Finds recipes that produce an item, including recipes from installed mods and custom machine recipe"
            + " types. Use it when the player asks how to make something. Returns the recipe type, recipe ID,"
            + " output count, and ingredients. The item may be an exact ID or a simple name."
            )
    public String getRecipesForItem(@P("An item ID or name, such as minecraft:diamond_pickaxe or potato_cannon")
            String itemId) {
        ResourceLocation wanted = resolveItem(itemId);

        List<RecipeHolder<?>> recipes = context.server().getRecipeManager().getRecipes().stream()
                .filter(holder -> wanted.equals(BuiltInRegistries.ITEM.getKey(result(holder).getItem())))
                .limit(MAX_RECIPES + 1L)
                .toList();
        if (recipes.isEmpty()) {
            return "There are no loaded recipes that produce " + wanted + ".";
        }

        boolean truncated = recipes.size() > MAX_RECIPES;
        String result = recipes.stream().limit(MAX_RECIPES)
                .map(this::describe)
                .collect(Collectors.joining("\n"));
        return "Loaded recipes for " + wanted + ":\n" + result
                + (truncated ? "\nMore recipes exist; only the first " + MAX_RECIPES + " are shown." : "");
    }

    private ResourceLocation resolveItem(String itemId) {
        String query = itemId.trim().toLowerCase();
        ResourceLocation exact = ResourceLocation.tryParse(query);
        if (exact != null && BuiltInRegistries.ITEM.containsKey(exact)) {
            return exact;
        }

        String path = exact != null ? exact.getPath() : query.replace(' ', '_');
        List<ResourceLocation> matches = BuiltInRegistries.ITEM.keySet().stream()
                .filter(id -> id.getPath().equals(path))
                .sorted()
                .toList();
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("There is no loaded item called " + itemId + ".");
        }
        throw new IllegalArgumentException("The item name " + itemId + " is ambiguous. Use one of: "
                + matches.stream().map(ResourceLocation::toString).collect(Collectors.joining(", ")) + ".");
    }

    private String describe(RecipeHolder<?> holder) {
        ItemStack output = result(holder);
        List<Ingredient> ingredients = holder.value().getIngredients();
        String layout;
        if (holder.value() instanceof ShapedRecipe shaped) {
            StringBuilder grid = new StringBuilder("shaped ").append(shaped.getWidth()).append('x')
                    .append(shaped.getHeight()).append(" grid: ");
            for (int row = 0; row < shaped.getHeight(); row++) {
                if (row > 0) {
                    grid.append(" / ");
                }
                grid.append('[');
                for (int column = 0; column < shaped.getWidth(); column++) {
                    if (column > 0) {
                        grid.append(", ");
                    }
                    grid.append(describeIngredient(ingredients.get(row * shaped.getWidth() + column)));
                }
                grid.append(']');
            }
            layout = grid.toString();
        } else {
            layout = "shapeless ingredients: [" + ingredients.stream()
                    .map(DataRetrievalTool::describeIngredient).collect(Collectors.joining(", ")) + ']';
        }
        ResourceLocation recipeType = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType());
        return "type " + recipeType + ", " + holder.id() + " -> " + output.getCount() + " "
                + BuiltInRegistries.ITEM.getKey(output.getItem()) + "; " + layout;
    }

    private ItemStack result(RecipeHolder<?> holder) {
        return holder.value().getResultItem(context.server().registryAccess());
    }

    private static String describeIngredient(Ingredient ingredient) {
        ItemStack[] choices = ingredient.getItems();
        if (choices.length == 0) {
            return "empty";
        }
        String text = Arrays.stream(choices)
                .limit(MAX_ALTERNATIVES_PER_INGREDIENT)
                .map(DataRetrievalTool::describeStack)
                .distinct()
                .collect(Collectors.joining(" | "));
        return choices.length > MAX_ALTERNATIVES_PER_INGREDIENT ? text + " | ..." : text;
    }

    private static String describeStack(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return stack.getCount() == 1 ? id : stack.getCount() + "x " + id;
    }
}
